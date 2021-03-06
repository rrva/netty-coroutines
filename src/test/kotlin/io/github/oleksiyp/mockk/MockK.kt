package io.github.oleksiyp.mockk

import javassist.ClassPool
import javassist.CtClass
import javassist.CtMethod
import javassist.Modifier
import javassist.bytecode.Bytecode
import javassist.bytecode.ClassFile
import javassist.util.proxy.MethodFilter
import javassist.util.proxy.MethodHandler
import javassist.util.proxy.ProxyFactory
import javassist.util.proxy.ProxyObject
import kotlinx.coroutines.experimental.runBlocking
import java.lang.reflect.Method
import java.nio.charset.Charset
import java.util.*
import java.util.Collections.synchronizedList
import java.util.Collections.synchronizedMap
import kotlin.coroutines.experimental.Continuation

// ---------------------------- USER FACING --------------------------------

interface MockK

fun MockK(value: Any) = value as MockK

inline fun <reified T> mockk(): T = MockKGateway.mockk(T::class.java)

class EqMatcher<T>(private val value: T) : Matcher<T> {
    override fun match(arg: T): Boolean = arg == value

    override fun toString(): String = "eq(" + MockKGateway.toString(value) + ")"
}

class ConstantMatcher<T>(private val value: Boolean) : Matcher<T> {
    override fun match(arg: T): Boolean = value

    override fun toString(): String = if (value) "any()" else "none()"
}

class LambdaMatcher<T>(private val matcher: (T) -> Boolean) : Matcher<T> {
    override fun match(arg: T): Boolean = matcher(arg)

    override fun toString(): String = "matcher()"
}


fun <T> every(mockBlock: suspend MockKScope.() -> T): MockKAnswerScope<T> {
    val callRecorder = MockKGateway.LOCATOR().callRecorder
    callRecorder.startRecording()
    runBlocking {
        MockKScope().mockBlock()
    }
    return MockKAnswerScope()
}

fun <T> verify(mockBlock: suspend MockKScope.() -> T): Unit {
    MockKGateway.LOCATOR().callRecorder.startVerification()
    runBlocking {
        MockKScope().mockBlock()
    }
    MockKGateway.LOCATOR().callRecorder.verify()
}

class MockKScope {
    inline fun <reified T> match(matcher: Matcher<T>): T = MockKGateway.matcherInCall(matcher)
    inline fun <reified T> match(noinline matcher: (T) -> Boolean): T = match(LambdaMatcher(matcher))
    inline fun <reified T> eq(value: T): T = match(EqMatcher(value))
    inline fun <reified T> any(): T = match(ConstantMatcher(true))
}


class MockKAnswerScope<T> {
    infix fun returns(returnValue: T?) {
        answers(ConstantAnswer(returnValue))
    }

    infix fun answers(answer: Answer<T?>) {
        MockKGateway.LOCATOR().callRecorder.answer(answer)
    }

    infix fun answers(answer: (Invocation) -> T?) {
        answers(object : Answer<T?> {
            override fun answer(invocation: Invocation): T? = answer(invocation)
        })
    }
}

// ---------------------------- INTERFACES --------------------------------
interface MockKGateway {

    val callRecorder: CallRecorder

    val valueGenerator: ValueGenerator

    companion object {
        val defaultImpl = MockKGatewayImpl()
        var LOCATOR: () -> MockKGateway = { defaultImpl }

        private val NO_ARGS_TYPE = Class.forName("\$NoArgsConstructorParamType")

        fun <T> mockk(cls: Class<T>): T {
            val factory = ProxyFactory()

            val obj = if (cls.isInterface) {
                factory.interfaces = arrayOf(cls, MockKInstance::class.java)
                factory.create(emptyArray(), emptyArray())
            } else {
                factory.interfaces = arrayOf(MockKInstance::class.java)
                factory.superclass = cls
                factory.create(arrayOf(MockKGateway.NO_ARGS_TYPE), arrayOf<Any?>(null))
            }
            (obj as ProxyObject).handler = MockKInstanceProxyHandler(cls, obj)
            return cls.cast(obj)
        }


        fun anyValue(type: Class<*>, block: () -> Any? = { Instantiator().instantiate(type) }): Any? {
            return when (type) {
                Void.TYPE -> Unit
                Boolean::class.java -> false
                Byte::class.java -> 0.toByte()
                Short::class.java -> 0.toShort()
                Int::class.java -> 0
                Long::class.java -> 0L
                Float::class.java -> 0.0F
                Double::class.java -> 0.0
                String::class.java -> ""
                Object::class.java -> Object()
                else -> block()
            }

        }

        inline fun <reified T> matcherInCall(matcher: Matcher<T>): T {
            val mockK = MockKGateway.LOCATOR()
            mockK.callRecorder.addMatcher(matcher)
            return mockK.valueGenerator.nextValue(T::class.java)
        }

        fun toString(obj: Any?): String {
            if (obj == null)
                return "null"
            if (obj is Method)
                return obj.toStr()
            return obj.toString()
        }

        fun Method.toStr() =
                name + "(" + parameterTypes.map { it.simpleName }.joinToString() + ")"

    }
}

interface ValueGenerator {
    fun start()

    fun <T> nextValue(cls: Class<T>): T

    fun booleanSpecialCase(nBoolean: Int): Int?

    fun nTh(value: Any): Int?

    fun end()

}

interface CallRecorder {
    fun startRecording()

    fun startVerification()

    fun addMatcher(matcher: Matcher<*>)

    fun addCall(invocation: Invocation): Any?

    fun answer(answer: Answer<*>)

    fun verify()
}

data class Invocation(val self: MockKInstance,
                      val method: Method,
                      val args: List<Any>) {
    override fun toString(): String {
        return "Invocation(self=$self, method=${MockKGateway.toString(method)}, args=$args)"
    }
}


data class InvocationMatcher(val self: Matcher<Any>,
                             val method: Matcher<Method>,
                             val args: List<Matcher<Any>>) {
    fun match(invocation: Invocation): Boolean {
        if (!self.match(invocation.self)) {
            return false
        }
        if (!method.match(invocation.method)) {
            return false
        }
        if (args.size != invocation.args.size) {
            return false
        }

        for (i in 0 until args.size) {
            if (!args[i].match(invocation.args[i])) {
                return false
            }
        }

        return true
    }
}

interface Matcher<in T> {
    fun match(arg: T): Boolean
}

interface Answer<T> {
    fun answer(invocation: Invocation): T
}

interface MockKInstance : MockK {
    fun ___type(): Class<*>

    fun ___addAnswer(matcher: InvocationMatcher, answer: Answer<*>)

    fun ___findAnswer(invocation: Invocation): Answer<*>

    fun ___childMockK(invocation: Invocation): MockKInstance
}

class MockKInstanceProxyHandler(private val cls: Class<*>,
                                private val obj: Any) : MethodHandler, MockKInstance {
    private val answers = synchronizedList(mutableListOf<Pair<InvocationMatcher, Answer<*>>>())
    private val mocks = synchronizedMap(hashMapOf<Invocation, MockKInstance>())

    override fun ___addAnswer(matcher: InvocationMatcher, answer: Answer<*>) {
        println(matcher.toString() + " -> " + answer)
        answers.add(Pair(matcher, answer))
    }

    override fun ___findAnswer(invocation: Invocation): Answer<*> {
        return synchronized(answers) {
            answers.firstOrNull { it.first.match(invocation) }?.second
                    ?: ConstantAnswer(MockKGateway.anyValue(invocation.method.returnType) {
                ___childMockK(invocation)
            })
        }
    }

    override fun ___type(): Class<*> = cls

    override fun toString() = "mockk<" + ___type().simpleName + ">()"

    override fun equals(other: Any?): Boolean {
        return obj === other
    }

    override fun hashCode(): Int {
        return System.identityHashCode(obj)
    }

    override fun ___childMockK(invocation: Invocation): MockKInstance {
        return mocks.computeIfAbsent(invocation, {
            MockKGateway.mockk(invocation.method.returnType) as MockKInstance
        })
    }

    override fun invoke(self: Any,
                        thisMethod: Method,
                        proceed: Method?,
                        args: Array<out Any>): Any? {

        findDeclaredMethod(this, thisMethod)?.let {
            return it.invoke(this, *args)
        }

        val argList = args.toList()
        val invocation = Invocation(self as MockKInstance, thisMethod, argList)
        return MockKGateway.LOCATOR().callRecorder.addCall(invocation)
    }

    private fun findDeclaredMethod(obj: Any,
                                   method: Method): Method? {
        return obj.javaClass.declaredMethods.find {
            it.name == method.name &&
                    Arrays.equals(it.parameterTypes, method.parameterTypes)
        }
    }
}

// ---------------------------- IMPLEMENTATION --------------------------------

data class ConstantAnswer<T>(val constantValue: T?) : Answer<T?> {
    override fun answer(invocation: Invocation) = constantValue

    override fun toString(): String {
        return "const($constantValue)"
    }
}

class MockKGatewayImpl : MockKGateway {
    val valueGeneratorTL = ThreadLocal.withInitial { ValueGeneratorImpl() }

    override val valueGenerator: ValueGenerator
        get() = valueGeneratorTL.get()

    class Wrapper(val value: Any) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Wrapper

            if (value !== other.value) return false

            return true
        }

        override fun hashCode(): Int {
            return System.identityHashCode(value)
        }

        override fun toString(): String {
            return value.javaClass.simpleName + "@" + hashCode()
        }
    }

    inner class ValueGeneratorImpl : ValueGenerator {
        val refs = hashMapOf<Wrapper, Int>()
        val vals = hashMapOf<Any, Int>()

        var nTotal = 0
        var nBooleans = 0
        var nBytes = 0.toByte()
        var nShorts = 0.toShort()
        var nInts = 0
        var nLongs = 0L
        var nDoubles = 0.0
        var nFloats = 0.0f
        var nStrings = 0

        fun reset() {
            vals.clear()
            nTotal = 0
            nBooleans = 0
            nBytes = 0
            nShorts = 0
            nInts = 0
            nLongs = 0
            nDoubles = 0.0
            nFloats = 0.0f
            nStrings = 0
        }

        override fun start() {
            reset()
        }

        fun checkByValue(cls: Class<*>): Boolean {
            return when(cls) {
                Boolean::class.java -> true
                Byte::class.java -> true
                Short::class.java -> true
                Int::class.java -> true
                Long::class.java -> true
                Float::class.java -> true
                Double::class.java -> true
                String::class.java -> true
                Object::class.java -> true
                else -> false
            }
        }

        override fun <T> nextValue(cls: Class<T>): T {
            val value = when (cls) {
                Boolean::class.java -> java.lang.Boolean(true)
                Byte::class.java -> java.lang.Byte(nBytes++)
                Short::class.java -> java.lang.Short(nShorts++)
                Int::class.java -> java.lang.Integer(nInts++)
                Long::class.java -> java.lang.Long(nLongs++)
                Float::class.java -> java.lang.Float(nFloats++)
                Double::class.java -> java.lang.Double(nDoubles++)
                String::class.java -> java.lang.String(nStrings++.toString())
                Object::class.java -> java.lang.Object()
                else -> Instantiator().instantiate(cls)
            }

            if (cls == java.lang.Boolean.TYPE) {
                vals.put(Wrapper("boolean" + nBooleans++), nTotal++)
            } else if (checkByValue(cls)) {
                vals.put(value, nTotal++)
            } else {
                refs.put(Wrapper(value), nTotal++)
            }
            return cls.cast(value)
        }

        override fun booleanSpecialCase(nBoolean: Int): Int? = vals[Wrapper("boolean" + nBoolean)]

        override fun nTh(value: Any): Int? {
            if (checkByValue(value.javaClass)) {
                return vals[value]
            } else {
                return refs[Wrapper(value)]
            }
        }

        override fun end() {
            reset()
        }
    }

    val callRecorderTL = ThreadLocal.withInitial { CallRecorderImpl() }
    override val callRecorder: CallRecorder
        get() = callRecorderTL.get()

    private data class MatchedCall(val invocation: Invocation, val matchers: List<Matcher<*>>)

    inner class CallRecorderImpl : CallRecorder {
        private val calls = mutableListOf<Invocation>()
        private val matchedCalls = mutableListOf<MatchedCall>()

        val matchers = mutableListOf<Matcher<*>>()

        var recordingLevel = -1
        var verificationLevel = -1

        override fun startRecording() {
            valueGenerator.start()
            recordingLevel = matchedCalls.size
        }

        override fun startVerification() {
            valueGenerator.start()
            verificationLevel = matchedCalls.size
        }

        override fun addMatcher(matcher: Matcher<*>) {
            matchers.add(matcher)
        }

        override fun addCall(invocation: Invocation): Any? {
            if (recordingLevel != -1 || verificationLevel != -1) {
                return addMatchedCall(invocation)
            } else {
                calls.add(invocation)
                return invocation.self.___findAnswer(invocation).answer(invocation)
            }

        }

        private fun addMatchedCall(invocation: Invocation): Any? {
            var nBoolean = 0
            val argMatchers = invocation.args.map {
                val n = if (it.javaClass == java.lang.Boolean.TYPE) {
                    valueGenerator.booleanSpecialCase(nBoolean)
                } else {
                    valueGenerator.nTh(it)
                }
                if (n != null) {
                    matchers.get(n)
                } else {
                    EqMatcher(it)
                }
            }.toMutableList()

            if (invocation.method.isSuspend()) {
                argMatchers[argMatchers.size - 1] = ConstantMatcher<Any>(true)
            }

            argMatchers.forEach{
                println(it)
            }

            matchedCalls.add(MatchedCall(invocation, argMatchers.toList()))

            valueGenerator.end()
            matchers.clear()

            return MockKGateway.anyValue(invocation.method.returnType) {
                invocation.self.___childMockK(invocation)
            }
        }

        override fun answer(answer: Answer<*>) {
            var ans = answer
            while (matchedCalls.size != recordingLevel) {
                matchedCalls.removeAt(matchedCalls.size - 1).let {
                    it.invocation.self.___addAnswer(
                            InvocationMatcher(
                                    EqMatcher(it.invocation.self),
                                    EqMatcher(it.invocation.method),
                                    it.matchers as List<Matcher<Any>>),
                            ans)
                    ans = ConstantAnswer(it.invocation.self)
                }
            }
            recordingLevel = -1
        }

        override fun verify() {
            val invokeMatcherList = mutableListOf<InvocationMatcher>()

            while (matchedCalls.size != verificationLevel) {
                matchedCalls.removeAt(matchedCalls.size - 1).let {
                    invokeMatcherList.add(InvocationMatcher(
                            EqMatcher(it.invocation.self),
                            EqMatcher(it.invocation.method),
                            it.matchers as List<Matcher<Any>>))
                }
            }

            for (invocation in calls) {
                invokeMatcherList.removeIf { it.match(invocation) }
            }

            verificationLevel = -1

            if (!invokeMatcherList.isEmpty()) {
                throw RuntimeException("verification failed " + invokeMatcherList +
                        " calls: " + calls)
            }

        }
    }

}

private fun Method.isSuspend(): Boolean {
    if (parameterCount == 0) {
        return false
    }
    return Continuation::class.java.isAssignableFrom(parameterTypes[parameterCount - 1])
}

// ---------------------------- BYTE CODE LEVEL --------------------------------

class Instantiator {
    val cp = ClassPool.getDefault()

    fun instantiate(cls: Class<*>): Any {
        val factory = ProxyFactory()

        val makeMethod = factory.javaClass.getDeclaredMethod("make")
        makeMethod.isAccessible = true

        val computeSignatureMethod = factory.javaClass.getDeclaredMethod("computeSignature",
                MethodFilter::class.java)
        computeSignatureMethod.isAccessible = true

        val allocateClassNameMethod = factory.javaClass.getDeclaredMethod("allocateClassName")
        allocateClassNameMethod.isAccessible = true

        if (cls == Charset::class.java) {
            println("Charset")
        }


        val proxyClsFile = if (cls.isInterface) {
            factory.interfaces = arrayOf(cls, MockKInstance::class.java)
            computeSignatureMethod.invoke(factory, MethodFilter { true })
            allocateClassNameMethod.invoke(factory)
            makeMethod.invoke(factory)
        } else {
            factory.interfaces = arrayOf(MockKInstance::class.java)
            factory.superclass = cls
            computeSignatureMethod.invoke(factory, MethodFilter { true })
            allocateClassNameMethod.invoke(factory)
            makeMethod.invoke(factory)
        } as ClassFile

        val proxyCls = cp.makeClass(proxyClsFile).toClass()

        val name = nameForInstantiator(proxyCls)
        val instantiatorCls =
                (cp.getOrNull(name)
                        ?: buildInstantiator(name, proxyCls)).toClass()

        val instantiator = instantiatorCls.newInstance()

        val instance = instantiatorCls.getMethod("newInstance")
                .invoke(instantiator)

        (instance as ProxyObject).handler = MethodHandler { self: Any, thisMethod: Method, proceed: Method, args: Array<Any?> ->

            if (thisMethod.name == "hashCode" && thisMethod.parameterCount == 0) {
                System.identityHashCode(self)
            } else if (thisMethod.name == "equals" &&
                    thisMethod.parameterCount == 1 &&
                    thisMethod.parameterTypes[0] == java.lang.Object::class.java) {
                self === args[0]
            } else {
                null
            }
        }


        return instance
    }

    protected fun nameForInstantiator(cls: Class<*>) = "inst." + cls.name + "\$Instantiator"

    private fun buildInstantiator(name: String, cls: Class<*>): CtClass {
        val instCls = cp.makeClass(name)
        val ctCls = cp.get(cls.name)

        val newInstanceMethod = CtMethod(ctCls, "newInstance", arrayOf(), instCls)
        newInstanceMethod.modifiers = Modifier.STATIC or Modifier.PUBLIC
        newInstanceMethod.exceptionTypes = arrayOf()
        newInstanceMethod.setBody("return null;")

        val bc = Bytecode(instCls.classFile.constPool)

        bc.addNew(ctCls)
        bc.addReturn(ctCls)


        val methodInfo = newInstanceMethod.methodInfo
        methodInfo.codeAttribute = bc.toCodeAttribute()
        methodInfo.rebuildStackMapIf6(cp, instCls.classFile2)
        ctCls.rebuildClassFile()

        instCls.addMethod(newInstanceMethod)

        return instCls
    }
}

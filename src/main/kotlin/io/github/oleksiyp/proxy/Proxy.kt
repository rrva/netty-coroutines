package io.github.oleksiyp.proxy

import io.github.oleksiyp.netty.NettyServer
import io.github.oleksiyp.netty.route
import io.netty.handler.codec.http.HttpResponseStatus
import kotlinx.coroutines.experimental.runBlocking

class Proxy {
    val connections = mutableListOf<ProxyConnection>()

    init {
        NettyServer(5555) {
            pipeline.addServerHttpCodec()
            pipeline.addServerWebSocketHandler(nettyDispatcher) {
                route("/proxy/(.+)/log") {
                    val connection = getConnection(regexGroups[1].toInt())
                    val unsubscribe = connection.log.subscribe {
                        runBlocking {
                            send(it)
                        }
                    }
                    try {
                        while (isActive) {
                            receive()
                        }
                    } finally {
                        unsubscribe()
                    }
                }
            }
            pipeline.addServerErrorHttpHandler(nettyDispatcher) {
                response("Error: " + cause.message!!, status = HttpResponseStatus.BAD_REQUEST)
            }
            pipeline.addServerHttpHandler(nettyDispatcher) {
                route("/proxy/listen/(.+)") {
                    val listenPort = regexGroups[1].toInt()

                    val connectPort = params.firstIntParam("port")
                            ?: throw RuntimeException("port not specified")

                    val connectHost = params.firstParam("host")
                            ?: throw RuntimeException("host not specified")

                    listen(listenPort, connectHost, connectPort)

                    response("Done")
                }
                route("/proxy/all") {
                    jsonResponse {
                        seq {
                            connections.forEach {
                                hash {
                                    "listenPort" .. it.listenPort
                                    "connectHost" .. it.connectHost
                                    "connectPort" .. it.connectPort
                                }
                            }
                        }
                    }
                }
                route("/proxy/stop/(.+)") {
                    val listenPort = regexGroups[1].toInt()

                    val connection = getConnection(listenPort)

                    connection.stop()
                    connections.remove(connection)

                    response("Stopped")
                }
                route("/proxy/(.+)/log") {
                    val listenPort = regexGroups[1].toInt()

                    getConnection(listenPort)

                    response("""
                        |<html>
                        |   <head>
                        |   <script>
                        |       var protocolPrefix = (window.location.protocol === 'https:') ? 'wss:' : 'ws:';
                        |       var connection = new WebSocket(protocolPrefix + '//' + location.host + '/proxy/$listenPort/log');
                        |       connection.onerror = function (error) {
                        |           console.log('WebSocket Error', error);
                        |       };
                        |       connection.onmessage = function (e) {
                        |           var log = document.getElementById("log")
                        |           log.appendChild(document.createTextNode(e.data));
                        |           log.appendChild(document.createElement("br"));
                        |           window.scrollBy(0, log.scrollHeight);
                        |       };
                        |
                        |   </script>
                        |   </head>
                        |   <body>
                        |       <pre id="log" />
                        |   </body>
                        |</html>
                    """.trimMargin())
                }
            }
        }
    }

    private fun getConnection(listenPort: Int): ProxyConnection {
        return connections.firstOrNull {
            it.listenPort == listenPort
        } ?: throw RuntimeException("not listeneing to " + listenPort)
    }


    private fun listen(listenPort: Int,
                       connectHost: String,
                       connectPort: Int) {

        connections.add(ProxyConnection(listenPort, connectHost, connectPort))
    }


    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            Proxy()
        }
    }
}


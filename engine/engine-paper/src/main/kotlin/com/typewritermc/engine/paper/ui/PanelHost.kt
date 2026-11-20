package com.typewritermc.engine.paper.ui

import com.typewritermc.engine.paper.logger
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.utils.config
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import org.koin.core.component.KoinComponent

class PanelHost : KoinComponent {
    private val enabled: Boolean by config("panel.enabled", false)
    private val port: Int by config("panel.port", 8080)
    private val bindAddress: String by config("panel.bind", "0.0.0.0", "网页面板监听地址；0.0.0.0监听所有网卡，127.0.0.1仅允许本机访问。修改后重启生效。")

    @Volatile private var server: ApplicationEngine? = null
    fun initialize() {
        if (!enabled) {
            // If we are developing the ui we don't want to start the server
            logger.warning("The panel is disabled while the websocket is enabled. This is only for development purposes. Please enable either both or none.")
            return
        }
        val classLoader = plugin.javaClass.classLoader

        // We have to construct the environment manually as Paper doesn't allow the default classloader
        // to see the web resources.
        val environment = applicationEngineEnvironment {
            this.classLoader = classLoader
            this.log = KtorSimpleLogger("ktor.application")
            this.module {
                routing {
                    staticResources("/", "web") {
                        default("index.html")
                    }
                }
            }
            connector {
                host = bindAddress
                port = this@PanelHost.port
            }
        }

        server = embeddedServer(factory = Netty, environment = environment).start(wait = false)
    }

    fun dispose() {
        val previous = server
        server = null
        previous?.stop()
    }
}

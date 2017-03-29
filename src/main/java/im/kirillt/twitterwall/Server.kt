package im.kirillt.twitterwall

import io.vertx.core.Vertx
import io.vertx.ext.web.handler.sockjs.SockJSHandler
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.StaticHandler
import io.vertx.ext.web.handler.sockjs.BridgeEventType
import io.vertx.kotlin.ext.web.handler.sockjs.BridgeOptions
import io.vertx.kotlin.ext.web.handler.sockjs.PermittedOptions

import java.text.DateFormat
import java.time.Instant
import java.util.*

fun main(args: Array<String>) {


    val vertx = Vertx.vertx()
    val server = vertx.createHttpServer()
    val router = Router.router(vertx)

    val PATH_PREFIX = "hashtags:"

    val opts = BridgeOptions(outboundPermitted = listOf(PermittedOptions(addressRegex = "$PATH_PREFIX.+")))

    val ebHandler = SockJSHandler.create(vertx).bridge(opts, { eventHandler ->
        when (eventHandler.type()) {
            BridgeEventType.REGISTER -> {
                println("register")
                println(eventHandler.rawMessage)
            }
            BridgeEventType.UNREGISTER -> {
                println("unregister")
                println(eventHandler.rawMessage)
            }
            else -> {
            }
        }
        eventHandler.complete(true)
    })
    router.route("/eventbus/*").handler(ebHandler)

    val staticHandler = StaticHandler.create()
    staticHandler.setCachingEnabled(false)

    router.route().handler(staticHandler)

    vertx.createHttpServer().requestHandler(router::accept).listen(8080)

    val eb = vertx.eventBus()

    val tags = listOf("foo", "bar")

    vertx.setPeriodic(5000, {
        for (tag in tags) {
            val timestamp = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date.from(Instant.now()))
            eb.publish(PATH_PREFIX + tag, "$timestamp: $tag")
        }
        println("periodic")
    })
}


package im.kirillt.twitterwall

import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.StaticHandler
import io.vertx.ext.web.handler.sockjs.BridgeEvent
import io.vertx.ext.web.handler.sockjs.BridgeEventType
import io.vertx.ext.web.handler.sockjs.SockJSHandler
import io.vertx.kotlin.ext.web.handler.sockjs.BridgeOptions
import io.vertx.kotlin.ext.web.handler.sockjs.PermittedOptions
import twitter4j.FilterQuery
import twitter4j.TwitterStream
import twitter4j.TwitterStreamFactory
import twitter4j.conf.Configuration
import twitter4j.conf.ConfigurationBuilder
import kotlin.collections.HashMap

class MainVerticle : AbstractVerticle() {

    //<set of tags> to map<address, clients count>
    val requestedTags = hashMapOf<Set<String>, HashMap<String, Int>>()
    var twitterStream: TwitterStream? = null

    override fun start() {
        twitterStream = TwitterStreamFactory(getTwitterConfig(config())).instance
        val server = vertx.createHttpServer()
        val router = Router.router(vertx)
        val opts = BridgeOptions(outboundPermitted = listOf(PermittedOptions(addressRegex = "$PATH_PREFIX.+")))

        val ebHandler = SockJSHandler.create(vertx).bridge(opts, { eventHandler ->

            when (eventHandler.type()) {

                BridgeEventType.REGISTER -> {
                    println("register")
                    val tags = getTags(eventHandler)
                    val addresses = requestedTags.getOrDefault(tags, hashMapOf())
                    val eventAddress = eventHandler.rawMessage.getString("address")
                    println(tags)
                    //TODO: fix concurrency problems
                    addresses.put(eventAddress, addresses.getOrDefault(eventAddress, 0) + 1)
                    requestedTags[tags] = addresses
                    if (addresses.size == 1) {
                        twitterStream?.filter(FilterQuery(*requestedTags.keys.flatten().toSet().map { '#' + it }.toTypedArray()))
                    }
                }
                BridgeEventType.UNREGISTER -> {
                    println("unregister")
                    val tags = getTags(eventHandler)
                    val addresses = requestedTags.getOrDefault(tags, hashMapOf())
                    val eventAddress = eventHandler.rawMessage.getString("address")
                    println(tags)
                    addresses.put(eventAddress, addresses.getOrDefault(eventAddress, 0) - 1)
                    if (addresses[eventAddress] == 0) {
                        addresses.remove(eventAddress)
                    }
                    if (addresses.isEmpty()) {
                        requestedTags.remove(tags)
                        twitterStream?.filter(FilterQuery(*requestedTags.keys.flatten().toSet().map { '#' + it }.toTypedArray()))
                    }
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

        val eventBus = vertx.eventBus()
        twitterStream?.onStatus { status ->
            val statusTags = status.hashtagEntities.map { it.text }.filterNotNull()
            println("get: $status")
            for ((tags, addresses) in requestedTags) {
                if (statusTags.containsAll(tags)) {
                    for (address in addresses.keys) {
                        eventBus.publish(address, status.text)
                    }
                }
            }
        }
        twitterStream?.onException { exception ->
            println(exception)
        }

        server.requestHandler(router::accept).listen(config().getInteger("port", 8080))
    }

    companion object {
        val PATH_PREFIX = "hashtags:"

        fun getTags(event: BridgeEvent): Set<String> {
            val address = event.rawMessage.getString("address", "")
            val tags = hashSetOf<String>()
            if (address.startsWith(PATH_PREFIX)) {
                tags.addAll(address.drop(PATH_PREFIX.length).split(" ").filter(String::isNotEmpty))
            }
            return tags
        }

        fun getTwitterConfig(globalConfig: JsonObject): Configuration {
            return ConfigurationBuilder()
                    .setOAuthConsumerKey(globalConfig.getString("consumerKey"))
                    .setOAuthConsumerSecret(globalConfig.getString("consumerSecret"))
                    .setOAuthAccessToken(globalConfig.getString("accessToken"))
                    .setOAuthAccessTokenSecret(globalConfig.getString("accessTokenSecret"))
                    .build()
        }

        data class Tweet(val text: String)
    }
}

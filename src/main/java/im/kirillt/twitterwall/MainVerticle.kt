package im.kirillt.twitterwall

import io.vertx.core.AbstractVerticle
import io.vertx.core.json.JsonObject
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.StaticHandler
import io.vertx.ext.web.handler.sockjs.BridgeEvent
import io.vertx.ext.web.handler.sockjs.BridgeEventType
import io.vertx.ext.web.handler.sockjs.SockJSHandler
import io.vertx.kotlin.ext.web.handler.sockjs.BridgeOptions
import io.vertx.kotlin.ext.web.handler.sockjs.PermittedOptions
import twitter4j.FilterQuery
import twitter4j.Status
import twitter4j.TwitterStream
import twitter4j.TwitterStreamFactory
import twitter4j.conf.Configuration
import twitter4j.conf.ConfigurationBuilder
import kotlin.collections.HashMap
import com.fasterxml.jackson.databind.ObjectMapper



class MainVerticle : AbstractVerticle() {
    private val logger = LoggerFactory.getLogger(MainVerticle::class.java)

    //<set of tags> to map<address, clients count>
    val requestedTags = hashMapOf<Set<String>, HashMap<String, Int>>()
    var allRequestedTags = hashSetOf<String>()
    var twitterStream: TwitterStream? = null

    override fun start() {
        twitterStream = TwitterStreamFactory(getTwitterConfig(config())).instance
        val server = vertx.createHttpServer()
        val router = Router.router(vertx)
        val opts = BridgeOptions(outboundPermitted = listOf(PermittedOptions(addressRegex = "$PATH_PREFIX.+")))

        val ebHandler = SockJSHandler.create(vertx).bridge(opts, { eventHandler ->
            val eventType = eventHandler.type()
            if (eventType == BridgeEventType.REGISTER || eventType == BridgeEventType.UNREGISTER) {
                synchronized(requestedTags, {
                    //TODO: fix concurrency problems
                    val clientTags = getTags(eventHandler)
                    val addresses = requestedTags.getOrPut(clientTags, { hashMapOf() })
                    val eventAddress = eventHandler.rawMessage.getString("address")
                    if (eventType == BridgeEventType.REGISTER) {
                        logger.info("$eventAddress registered for tags: $clientTags")
                        addresses.put(eventAddress, addresses.getOrDefault(eventAddress, 0) + 1)
                        if (!allRequestedTags.containsAll(clientTags)) {
                            allRequestedTags.addAll(clientTags)
                            twitterStream?.filter(FilterQuery(*allRequestedTags.map { '#' + it }.toTypedArray()))
                        }
                    } else {
                        logger.info("$eventAddress unregistered for tags: $clientTags")
                        addresses.put(eventAddress, addresses.getOrDefault(eventAddress, 0) - 1)
                        if (addresses[eventAddress] == 0) {
                            addresses.remove(eventAddress)
                        }
                        if (addresses.isEmpty()) {
                            requestedTags.remove(clientTags)
                            val restTags = requestedTags.keys.flatten().toHashSet()
                            if (restTags.size < allRequestedTags.size) {
                                allRequestedTags = restTags
                                twitterStream?.filter(FilterQuery(*allRequestedTags.map { '#' + it }.toTypedArray()))
                            }
                        }
                    }

                })
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
            logger.debug("get: $status")
            val jsonStr = ObjectMapper().writeValueAsString(Tweet.fromStatus(status))
            for ((tags, addresses) in requestedTags) {
                if (statusTags.containsAll(tags)) {
                    for (address in addresses.keys) {
                        eventBus.publish(address, jsonStr)
                    }
                }
            }
        }
        twitterStream?.onException { exception ->
            logger.error(exception.message)
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

        data class Tweet(val text: String, val userName: String,
                         val userPicUrl: String, val time: Long, val id: String) {
            companion object {
                fun fromStatus(status: Status) =
                        Tweet(status.text, status.user.screenName,
                                status.user.miniProfileImageURLHttps, status.createdAt.time, "${status.id}")

            }
        }
    }
}

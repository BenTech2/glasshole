package com.glasshole.phone.plugindir.worker

/**
 * Registry mapping primitive names (as referenced from plugin schemas)
 * to factory functions that produce fresh instances. Primitives are
 * instance-per-worker, not singletons — each plugin's worker gets its
 * own state.
 *
 * This is the sole extension point for adding a new *kind* of worker.
 * Plugins describe their worker declaratively in JSON and reference
 * the primitive name; if that name isn't in the registry the worker is
 * silently ignored (until a phone update adds it). Plugins themselves
 * never contribute code to the phone — they pick from whatever the
 * phone app knows how to do.
 */
object WorkerRegistry {

    private val factories = mutableMapOf<String, () -> WorkerPrimitive>()

    fun register(name: String, factory: () -> WorkerPrimitive) {
        factories[name] = factory
    }

    fun create(name: String): WorkerPrimitive? = factories[name]?.invoke()

    fun names(): Set<String> = factories.keys

    /**
     * Seed the registry with built-in primitives on app startup. Called
     * once from BridgeService.onCreate.
     */
    fun registerBuiltIns() {
        register("http-post") { HttpPostPrimitive() }
        register("location-stream") { LocationStreamPrimitive() }
        register("chat-twitch") { TwitchChatPrimitive() }
        register("chat-youtube") { YouTubeChatPrimitive() }
        register("history-store") { HistoryStorePrimitive() }
        // Additional primitives register here as they're added:
        //   register("http-poll") { HttpPollPrimitive() }
        //   register("tcp-line")  { TcpLinePrimitive() }
    }
}

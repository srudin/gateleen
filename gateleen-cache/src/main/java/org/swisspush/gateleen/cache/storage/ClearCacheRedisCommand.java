package org.swisspush.gateleen.cache.storage;

import io.vertx.core.Promise;
import io.vertx.redis.client.RedisAPI;
import org.slf4j.Logger;
import org.swisspush.gateleen.core.lua.LuaScriptState;
import org.swisspush.gateleen.core.lua.RedisCommand;
import org.swisspush.gateleen.core.util.RedisUtils;

import java.util.List;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class ClearCacheRedisCommand implements RedisCommand {

    private final LuaScriptState luaScriptState;
    private final List<String> keys;
    private final List<String> arguments;
    private final Promise<Long> promise;
    private final RedisAPI redisAPI;
    private final Logger log;

    public ClearCacheRedisCommand(LuaScriptState luaScriptState, List<String> keys, List<String> arguments,
                                  RedisAPI redisAPI, Logger log, final Promise<Long> promise) {
        this.luaScriptState = luaScriptState;
        this.keys = keys;
        this.arguments = arguments;
        this.redisAPI = redisAPI;
        this.log = log;
        this.promise = promise;
    }

    @Override
    public void exec(int executionCounter) {
        List<String> args= RedisUtils.toPayload(luaScriptState.getSha(), keys.size(), keys, arguments);
        redisAPI.evalsha(args, event -> {
            if(event.succeeded()){
                Long clearedItemsCount = event.result().toLong();
                promise.complete(clearedItemsCount);
            } else {
                String message = event.cause().getMessage();
                if(message != null && message.startsWith("NOSCRIPT")) {
                    log.warn("ClearCacheRedisCommand script couldn't be found, reload it");
                    log.warn("amount the script got loaded: " + executionCounter);
                    if(executionCounter > 10) {
                        promise.fail("amount the script got loaded is higher than 10, we abort");
                    } else {
                        luaScriptState.loadLuaScript(new ClearCacheRedisCommand(luaScriptState, keys, arguments, redisAPI, log, promise), executionCounter);
                    }
                } else {
                    promise.fail("ClearCacheRedisCommand request failed with message: " + message);
                }
            }
        });

    }
}

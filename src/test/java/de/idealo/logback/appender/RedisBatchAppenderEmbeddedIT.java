package de.idealo.logback.appender;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.util.StatusPrinter;
import de.idealo.logback.appender.utils.MDCUtils;
import org.junit.*;
import org.skyscreamer.jsonassert.JSONAssert;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.embedded.RedisServer;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Integration test that uses an <a href="https://github.com/kstyrc/embedded-redis">embedded redis</a>.
 */
public class RedisBatchAppenderEmbeddedIT {

    private static final int LOCAL_REDIS_PORT = 6379;
    private static final int REDIS_IDLE_TIMEOUT_IN_SECONDS = 3;
    private static final int SLEEP_TIME_IN_SECONDS_FOR_CONNECTION_TIMEOUT = 5;

    private static RedisServer redisServer;
    private static Jedis redisClient;
    private static MDCUtils mdcUtils;

    @BeforeClass
    public static void beforeClass() throws Exception {
/*
        redisServer = new RedisServerBuilder()
                .port(LOCAL_REDIS_PORT)
                .setting("timeout " + REDIS_IDLE_TIMEOUT_IN_SECONDS)
                .build();
*/
        redisServer = new RedisServer(LOCAL_REDIS_PORT);
        redisServer.start(); // this waits until server write start notification to stdout (!)
        log().info("started redis server");

        redisClient = new JedisPool("localhost", LOCAL_REDIS_PORT).getResource(); // uses localhost / local port
        log().info("initialized redis client");
        logStatus();
    }

    @Before
    public void before() {
        mdcUtils = new MDCUtils();
    }

    @AfterClass
    public static void afterClass() throws Exception {
        if (redisClient != null) {
            redisClient.close();
        }
        log().info("closed redis client");

        if (redisServer != null) {
            redisServer.stop(); // does not seem to be called if assertion fails...
        }
        log().info("stopped redis server");
    }

    @After
    public void after() {
        mdcUtils.clear();
    }

    @Test
    public void allMessagesAreSuccessfullyLoggedBeforeAndAfterConnectionTimeout() throws Exception {
        messageIsSuccessfullyLoggedForSequenceNumber(0);
        log().info("all messages are successfully logged before connection timeout");

        log().info("waiting " + SLEEP_TIME_IN_SECONDS_FOR_CONNECTION_TIMEOUT + " seconds before logging to redis again");
        Thread.sleep(SLEEP_TIME_IN_SECONDS_FOR_CONNECTION_TIMEOUT * 1000L);

        // get fresh connection from the pool; previous one has meanwhile timed out
        redisClient = new JedisPool("localhost", LOCAL_REDIS_PORT).getResource();

        messageIsSuccessfullyLoggedForSequenceNumber(1);
        log().info("all messages are successfully logged after connection timeout");
    }

    private void messageIsSuccessfullyLoggedForSequenceNumber(int expectedSequenceNumber) throws Exception {
        redisClient.del("integrationtest");
        logMessages(1, 1, "dummy");
        String expectedLoggedMessage = "{" +
                "\"seq\":\"" + expectedSequenceNumber + "\"," +
                "\"someNumber\":\"1\"," +
                "\"someString\":\"hello\"," +
                "\"message\":\"dummy:0\"," +
                "\"logger\":\"LoggingTest\"," +
                "\"thread\":\"main\"," +
                "\"level\":\"INFO\"," +
                "\"file\":\"RedisBatchAppenderEmbeddedIT.java\"" +
                "}";
        log().debug("expected logged message: {}", expectedLoggedMessage);

        String loggedMessage = redisClient.lindex("integrationtest", 0);
        log().debug("logged message: {}", loggedMessage);

        assertThat(loggedMessage, is(not(nullValue())));
        JSONAssert.assertEquals(expectedLoggedMessage, loggedMessage, false);
    }

    private void logMessages(long count, int statsInterval, String prefix) {
        Logger loggingTest = createRedisLogger();

        for (int i = 0; i < count; i++) {
            initMDC();
            loggingTest.info(prefix + ":" + i);

            int messagesSent = i + 1;
            if (messagesSent % statsInterval == 0) {
                log().info("Messages sent so far: " + messagesSent);
            }
        }
        log().info("Messages sent TOTAL: " + count);
    }

    private void initMDC() {
        mdcUtils.clear()
                .initSeq()
                .putIfPresent("someNumber", 1L)
                .putIfPresent("someString", "hello");
    }

    // lazy initialization to defer loading of logback XML until embedded redis is started
    private static org.slf4j.Logger log() {
        return LoggerFactory.getLogger(RedisBatchAppenderEmbeddedIT.class);
    }

    private static void logStatus() {
        // assume SLF4J is bound to logback in the current environment
        LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
        // print logback's internal status
        StatusPrinter.print(lc);
    }

    private static Logger createRedisLogger() {
        LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
        return lc.getLogger("LoggingTest");
    }
}

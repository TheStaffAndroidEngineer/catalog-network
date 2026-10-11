package staff.android.network.server

import java.util.concurrent.CountDownLatch

fun main(args: Array<String>) {
    val server = FixtureServer(args.firstOrNull()?.toInt() ?: 8787)
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    println("Catalog fixture listening at ${server.baseUrl}")
    println("GET /history; GET /control?mode=reset|invalid|malformed|empty|hold|partial|release|expire|http503")
    CountDownLatch(1).await()
}

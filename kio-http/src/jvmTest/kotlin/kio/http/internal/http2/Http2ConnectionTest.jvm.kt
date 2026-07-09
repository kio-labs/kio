package kio.http.internal.http2

import kio.async.PollerFactory
import kio.async.poller.select.Select

class SelectHttp2ConnectionTest: Http2ConnectionTest() {
    override val poller: PollerFactory = Select
}
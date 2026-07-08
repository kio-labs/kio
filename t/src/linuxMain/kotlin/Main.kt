import kio.async.poller.uring.LinuxUring
import kio.async.runPollEventLoop

fun main() = runPollEventLoop(LinuxUring){
}
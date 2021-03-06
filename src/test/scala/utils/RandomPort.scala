package utils

import java.net.ServerSocket

object RandomPort {
  def apply(): Int = {
    val socket = new ServerSocket(0)
    socket.setReuseAddress(true)
    val port = socket.getLocalPort
    socket.close()
    port
  }
}

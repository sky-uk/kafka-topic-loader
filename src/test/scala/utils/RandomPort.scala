package utils

import java.net.ServerSocket

import scala.util.Using

object RandomPort {
  def apply(): Int = Using.resource(new ServerSocket(0)) { socket =>
    socket.setReuseAddress(true)
    socket.getLocalPort
  }
}

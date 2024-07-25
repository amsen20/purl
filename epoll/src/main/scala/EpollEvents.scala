package epoll

class EpollEvents {
  private var isInput = false
  private var isOutput = false
  private var isError = false
  private var isPri = false
  private var isReadingHangUp = false
  private var isHangUp = false

  def input(): EpollEvents =
    isInput = true
    this

  def output(): EpollEvents =
    isOutput = true
    this

  def error(): EpollEvents =
    isError = true
    this

  def readingHangUp(): EpollEvents =
    isReadingHangUp = true
    this

  def hangUp(): EpollEvents =
    isHangUp = true
    this

  def pri(): EpollEvents =
    isPri = true
    this

  private[epoll] def getMask(): Int =
    var mask = 0
    if isInput then mask |= epoll.EPOLLIN
    if isOutput then mask |= epoll.EPOLLOUT
    if isError then mask |= epoll.EPOLLERR
    if isPri then mask |= epoll.EPOLLPRI
    if isReadingHangUp then mask |= epoll.EPOLLHUP
    if isHangUp then mask |= epoll.EPOLLRDHUP
    mask

}

object EpollEvents:
  private[epoll] def fromMask(mask: Int): EpollEvents =
    val ret = EpollEvents()
    if (mask & epoll.EPOLLIN) > 0 then ret.isInput = true
    if (mask & epoll.EPOLLOUT) > 0 then ret.isOutput = true
    if (mask & epoll.EPOLLERR) > 0 then ret.isError = true
    if (mask & epoll.EPOLLPRI) > 0 then ret.isPri = true
    if (mask & epoll.EPOLLHUP) > 0 then ret.isReadingHangUp = true
    if (mask & epoll.EPOLLRDHUP) > 0 then ret.isHangUp = true

    ret

class EpollInputEvents extends EpollEvents:
  private var isEdgeTriggered = false
  private var isOneShot = false
  private var isWakeUp = false
  private var isExclusive = false

  def edgeTriggered(): EpollInputEvents =
    isEdgeTriggered = true
    this

  def oneShot(): EpollInputEvents =
    isOneShot = true
    this

  def wakeUp(): EpollInputEvents =
    isWakeUp = true
    this

  def exclusive(): EpollInputEvents =
    isExclusive = true
    this

  override private[epoll] def getMask(): Int =
    var mask = super.getMask()
    if isEdgeTriggered then mask |= epoll.EPOLLET
    if isOneShot then mask |= epoll.EPOLLONESHOT
    if isWakeUp then mask |= epoll.EPOLLWAKEUP
    if isExclusive then mask |= epoll.EPOLLEXCLUSIVE
    mask

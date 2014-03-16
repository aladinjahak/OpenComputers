package li.cil.oc.common.tileentity

import cpw.mods.fml.common.Optional
import cpw.mods.fml.relauncher.{Side, SideOnly}
import li.cil.oc.api.Machine
import li.cil.oc.api.machine.Owner
import li.cil.oc.api.network._
import li.cil.oc.client.Sound
import li.cil.oc.server.{PacketSender => ServerPacketSender, driver}
import li.cil.oc.Settings
import li.cil.oc.util.ExtendedNBT._
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.nbt.{NBTTagString, NBTTagCompound}
import net.minecraft.util.ChatComponentTranslation
import net.minecraftforge.common.util.Constants.NBT
import net.minecraftforge.common.util.ForgeDirection
import scala.collection.mutable
import stargatetech2.api.bus.IBusDevice

// See AbstractBusAware as to why we have to define the IBusDevice here.
@Optional.Interface(iface = "stargatetech2.api.bus.IBusDevice", modid = "StargateTech2")
trait Computer extends Environment with ComponentInventory with Rotatable with BundledRedstoneAware with AbstractBusAware with IBusDevice with Analyzable with Owner {
  def isRemote: Boolean

  private lazy val _computer = if (isRemote) null else Machine.create(this)

  def computer = _computer

  override def node = if (isServer) computer.node else null

  override lazy val isClient = computer == null

  private var _isRunning = false

  private var hasChanged = false

  private val _users = mutable.Set.empty[String]

  // ----------------------------------------------------------------------- //

  // Note: we implement IContext in the TE to allow external components to cast
  // their owner to it (to allow interacting with their owning computer).

  override def address = computer.address

  override def canInteract(player: String) =
    if (isServer) computer.canInteract(player)
    else !Settings.get.canComputersBeOwned ||
      _users.isEmpty || _users.contains(player)

  override def isRunning = _isRunning

  @SideOnly(Side.CLIENT)
  def setRunning(value: Boolean) = {
    _isRunning = value
    world.markBlockForUpdate(x, y, z)
    if (_isRunning) Sound.startLoop(this, "computer_running", 0.5f, 50 + world.rand.nextInt(50))
    else Sound.stopLoop(this)
    this
  }

  override def isPaused = computer.isPaused

  override def start() = computer.start()

  override def pause(seconds: Double) = computer.pause(seconds)

  override def stop() = computer.stop()

  override def signal(name: String, args: AnyRef*) = computer.signal(name, args: _*)

  // ----------------------------------------------------------------------- //

  override def markAsChanged() = hasChanged = true

  override def installedComponents = components collect {
    case Some(component) => component
  }

  override def onMachineConnect(node: Node) = this.onConnect(node)

  override def onMachineDisconnect(node: Node) = this.onDisconnect(node)

  def hasAbstractBusCard = items.exists {
    case Some(item) => computer.isRunning && driver.item.AbstractBusCard.worksWith(item)
    case _ => false
  }

  def hasRedstoneCard = items.exists {
    case Some(item) => computer.isRunning && driver.item.RedstoneCard.worksWith(item)
    case _ => false
  }

  def users: Iterable[String] =
    if (isServer) computer.users
    else _users

  @SideOnly(Side.CLIENT)
  def users_=(list: Iterable[String]) {
    _users.clear()
    _users ++= list
  }

  // ----------------------------------------------------------------------- //

  override def updateEntity() {
    if (isServer) {
      // If we're not yet in a network we might have just been loaded from disk,
      // meaning there may be other tile entities that also have not re-joined
      // the network. We skip the update this round to allow other tile entities
      // to join the network, too, avoiding issues of missing nodes (e.g. in the
      // GPU which would otherwise loose track of its screen).
      if (addedToNetwork) {
        computer.update()

        if (hasChanged) {
          hasChanged = false
          world.markTileEntityChunkModified(x, y, z, this)
        }

        if (_isRunning != computer.isRunning) {
          _isRunning = computer.isRunning
          isOutputEnabled = hasRedstoneCard
          isAbstractBusAvailable = hasAbstractBusCard
          ServerPacketSender.sendComputerState(this)
        }

        updateRedstoneInput()
        updateComponents()
      }
    }

    super.updateEntity()
  }

  // Note: chunk unload is handled by sound via event handler.
  override def invalidate() {
    super.invalidate()
    Sound.stopLoop(this)
  }

  // ----------------------------------------------------------------------- //

  override def readFromNBT(nbt: NBTTagCompound) {
    super.readFromNBT(nbt)
    // God, this is so ugly... will need to rework the robot architecture.
    // This is required for loading auxiliary data (kernel state), because the
    // coordinates in the actual robot won't be set properly, otherwise.
    this match {
      case proxy: RobotProxy =>
        proxy.robot.xCoord = xCoord
        proxy.robot.yCoord = yCoord
        proxy.robot.zCoord = zCoord
      case _ =>
    }
    computer.load(nbt.getCompoundTag(Settings.namespace + "computer"))
  }

  override def writeToNBT(nbt: NBTTagCompound) {
    super.writeToNBT(nbt)
    if (computer != null) {
      nbt.setNewCompoundTag(Settings.namespace + "computer", computer.save)
    }
  }

  @SideOnly(Side.CLIENT)
  override def readFromNBTForClient(nbt: NBTTagCompound) {
    super.readFromNBTForClient(nbt)
    _isRunning = nbt.getBoolean("isRunning")
    _users.clear()
    _users ++= nbt.getTagList("users", NBT.TAG_STRING).map((list, index) => list.getStringTagAt(index))
    if (_isRunning) Sound.startLoop(this, "computer_running", 0.5f, 1000 + world.rand.nextInt(2000))
  }

  override def writeToNBTForClient(nbt: NBTTagCompound) {
    super.writeToNBTForClient(nbt)
    nbt.setBoolean("isRunning", isRunning)
    nbt.setNewTagList("users", computer.users.map(user => new NBTTagString(user)))
  }

  // ----------------------------------------------------------------------- //

  override def markDirty() {
    super.markDirty()
    if (isServer) {
      computer.architecture.recomputeMemory()
      isOutputEnabled = hasRedstoneCard
      isAbstractBusAvailable = hasAbstractBusCard
    }
  }

  override protected def onRotationChanged() {
    super.onRotationChanged()
    checkRedstoneInputChanged()
  }

  override protected def onRedstoneInputChanged(side: ForgeDirection) {
    super.onRedstoneInputChanged(side)
    computer.signal("redstone_changed", computer.node.address, Int.box(toLocal(side).ordinal()))
  }

  // ----------------------------------------------------------------------- //

  override def onAnalyze(player: EntityPlayer, side: Int, hitX: Float, hitY: Float, hitZ: Float) = {
    computer.lastError match {
      case value if value != null =>
        player.addChatMessage(new ChatComponentTranslation(
          Settings.namespace + "gui.Analyzer.LastError", new ChatComponentTranslation(value)))
      case _ =>
    }
    player.addChatMessage(new ChatComponentTranslation(
      Settings.namespace + "gui.Analyzer.Components", computer.componentCount + "/" + maxComponents))
    val list = users
    if (list.size > 0) {
      player.addChatMessage(new ChatComponentTranslation(
        Settings.namespace + "gui.Analyzer.Users", list.mkString(", ")))
    }
    Array(computer.node)
  }
}

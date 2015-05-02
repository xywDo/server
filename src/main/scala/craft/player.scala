package craft

import scala.math.max

import akka.actor.{ Actor, Props, ActorRef, Stash, PoisonPill }

import spray.json._

case class Item(amount: Int, w: Int)

case class Inventory(items: Map[String, Item])

case class Player(nick: String, password: String, position: protocol.Position,
  active: Int, inventory: Inventory)

class PlayerActor(pid: Int, nick: String, password: String, client: ActorRef, world: ActorRef, store: ActorRef, startingPosition: protocol.Position) extends Actor with Stash with utils.Scheduled {
  import PlayerActor._
  import WorldActor._
  import World.ChunkSize
  import DefaultJsonProtocol._
  import PlayerStorageActor._

  implicit val itemFormat = jsonFormat2(Item)
  implicit val inventoryFormat = jsonFormat1(Inventory)
  implicit val protocolFormat = jsonFormat5(protocol.Position)
  implicit val playerFormat = jsonFormat5(Player)

  var sentChunks = Set.empty[ChunkPosition]
  var data: Player = null
  var maxChunksToSend = 0

  schedule(5000, StoreData)

  store ! Load(nick)

  def receive = {
    case Loaded(_, Some(json)) =>
      val newData = (new String(json)).parseJson.convertTo[Player]
      if(newData.password == password) {
        data = newData
        context.become(ready)
        client ! PlayerInfo(pid, nick, self, data.position)
        unstashAll()
      } else {
        client ! PoisonPill
        context.stop(self)
      }
    case Loaded(_, None) =>
      data = Player(nick, password, startingPosition, 0, Inventory(Map()))
      context.become(ready)
      client ! PlayerInfo(pid, nick, self, data.position)
      unstashAll()
    case _ =>
      stash()
  }

  def sendChunks() {
    val visible = visibleChunks(Position(data.position), 7)
    sentChunks = sentChunks & visible
    val diff = visible &~ sentChunks
    val toSend = diff.take(maxChunksToSend)
    for(chunk <- toSend) {
      world ! SendBlocks(client, chunk, None)
      maxChunksToSend -= 1
    }
    sentChunks ++= toSend
  }

  def update(position: protocol.Position) {
    data = data.copy(position = position)
    sendChunks()
  }

  def update(inventory: Inventory) {
    data = data.copy(inventory = inventory)
  }

  def action(pos: Position, button: Int) = {
    button match {
      case 1 =>
        val l = LSystem(Seq(
          DeterministicProductionRule("cc", "c[&[c][-c][--c][+c]]c[&[c][-c][--c][+c]]"),
          DeterministicProductionRule("a","aa"),
          ProbabilisticProductionRule("c",
            Seq(
              (20, "c[&[d]]"),
              (20, "c[&[+d]]"),
              (20, "c[&[-d]]"),
              (20, "c[&[--d]]"),
              (20, "cc")
            )),
          ProbabilisticProductionRule("aa", Seq((50, "a[&[c][-c][--c][+c]]"), (50, "bbba")))
        ))
        val m = BlockMachine(Map('a'-> 5, 'b' -> 5, 'c' -> 15, 'd' -> 15))
        val tree = l.iterate("a[&[c][-c][--c][+c]]c", 5)
        println(tree)
        val blocks = m.interpret(tree, pos)
        for(b <- blocks)
          world ! PutBlock(self, b._1, b._2)
      case 2 =>
        val inventory = data.inventory
        val active = data.active.toString
        inventory.items.get(active).map { item =>
          val updatedItem = item.copy(amount = item.amount - 1)
          if(updatedItem.amount > 0)
            update(inventory.copy(items =
              inventory.items + (active -> updatedItem)))
          else
            update(inventory.copy(items =
              inventory.items - active))
          world ! PutBlock(self, pos, item.w)
          sender ! InventoryUpdate(Map(active -> updatedItem))
        }
    }
  }

  def putInInventory(block: Byte) {
    val item = Item(1, block.toInt)
    val inventory = data.inventory
    inventory.items.find {
      case (position, item) =>
        item.w == block && item.amount < 64
    } match {
      case Some((position, item)) =>
        val itemUpdate = position -> item.copy(amount = item.amount + 1)
        update(inventory.copy(items = inventory.items + itemUpdate))
        client ! InventoryUpdate(Map(itemUpdate))
      case None =>
        (0 until 9).find { i =>
          !inventory.items.get(i.toString).isDefined
        } map { i =>
          val itemUpdate = i.toString -> Item(1, block)
          update(inventory.copy(items = inventory.items + itemUpdate))
          client ! InventoryUpdate(Map(itemUpdate))
        }
    }
  }

  override def postStop {
    if(data != null)
      store ! Store(nick, data.toJson.compactPrint.getBytes)
    world ! PlayerLogout(pid)
  }

  def ready: Receive = {
    case p: protocol.Position =>
      update(p)
      world ! PlayerMovement(pid, data.position)
    case b: protocol.SendBlock =>
      client ! b
    case SendInventory =>
      sender ! InventoryUpdate(data.inventory.items)
      sender ! InventoryActiveUpdate(data.active)
    case ActivateInventoryItem(newActive) if(newActive >= 0 && newActive < 9) =>
      data = data.copy(active = newActive)
      sender ! InventoryActiveUpdate(data.active)
    case Action(pos, button) =>
      action(pos, button)
    case ReceiveBlock(block) =>
      putInInventory(block)
    case p: PlayerMovement =>
      client ! p
    case p: PlayerNick =>
      client ! p
    case SendInfo(to) =>
      to ! PlayerMovement(pid, data.position)
      to ! PlayerNick(pid, data.nick)
    case StoreData =>
      store ! Store(nick, data.toJson.compactPrint.getBytes)
    case l: PlayerLogout =>
      client ! l
    case IncreaseChunks(amount) =>
      maxChunksToSend += amount
      sendChunks()
  }
}

object PlayerActor {
  case object SendInventory
  case object StoreData
  case class ReceiveBlock(q: Byte)
  case class PlayerMovement(pid: Int, pos: protocol.Position)
  case class PlayerLogout(pid: Int)
  case class PlayerInfo(pid: Int, nick: String, actor: ActorRef, pos: protocol.Position)
  case class PlayerNick(pid: Int, nick: String)
  case class ActivateInventoryItem(activate: Int)
  case class InventoryUpdate(items: Map[String, Item])
  case class InventoryActiveUpdate(active: Int)
  case class Action(pos: Position, button: Int)
  case class SendInfo(to: ActorRef)
  case class IncreaseChunks(amount: Int)

  val LoadYChunks = 5
  def props(pid: Int, nick: String, password: String, client: ActorRef, world: ActorRef, store: ActorRef, startingPosition: protocol.Position) = Props(classOf[PlayerActor], pid, nick, password, client, world, store, startingPosition)


  def visibleChunks(position: Position, visibility: Int): Set[ChunkPosition] = {
    val range = -visibility to visibility
    val chunk = position.chunk
    (for(p <- range; q <- range; k <-range) yield {
      chunk.translate(p, q, k)
    }).filter(_.k >= 0).toSet
  }
}

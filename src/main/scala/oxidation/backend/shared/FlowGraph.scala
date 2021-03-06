package oxidation
package backend
package shared

import codegen.Name
import scala.collection.mutable

class FlowGraph(val blocks: Map[Name, ir.Block], val entry: Name) {

  lazy val parents: Memo[Name, Set[Name]] = Memo { n =>
    blocks.keys.filter(children(_) contains n).toSet
  }

  lazy val children: Memo[Name, Set[Name]] = Memo { n =>
    blocks(n).flow match {
      case ir.FlowControl.Return(_) | ir.FlowControl.Unreachable => Set.empty
      case ir.FlowControl.Branch(_, t, f) => Set(t, f)
      case ir.FlowControl.Goto(l) => Set(l)
    }
  }

  def successors(name: Name): Set[Name] = {
    def go(name: Name, found: Set[Name]): Set[Name] = {
      val search = children(name) -- found
      search.foldLeft(found ++ search)((f, b) => f ++ go(b, f))
    }
    go(name, Set.empty)
  }

  def predecessors(name: Name): Set[Name] = {
    def go(name: Name, found: Set[Name]): Set[Name] = {
      val search = parents(name) -- found
      search.foldLeft(found ++ search)((f, b) => f ++ go(b, f))
    }
    go(name, Set.empty)
  }

  def postorder: Vector[Name] = {
    val visited = mutable.Set.empty[Name]
    def go(name: Name): Vector[Name] = {
      visited += name
      (children(name) -- visited).toVector.flatMap(go(_)) :+ name
    }
    go(entry)
  }

}

object FlowGraph {

  def apply(blocks: Map[Name, ir.Block], entry: Name): FlowGraph = new FlowGraph(blocks, entry)
  def apply(blocks: Seq[ir.Block]): FlowGraph = FlowGraph(blocks.map(b => b.name -> b).toMap, blocks.head.name)

}

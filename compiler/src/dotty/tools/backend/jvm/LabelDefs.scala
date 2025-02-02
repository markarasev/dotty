package dotty.tools.backend.jvm

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags._
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.transform.MegaPhase._

import scala.collection.mutable

/**
 * Verifies that each Label DefDef has only a single address to jump back and
 * reorders them such that they are not nested and this address is a
 * fall-through address for the JVM.
 *
 * ```scala
 * <label> def foo(i: Int) = {
 *   <label> def bar = 0
 *   <label> def dough(i: Int) = if (i == 0) bar else foo(i-1)
 *   dough(i)
 * }
 *
 * foo(100)
 * ```
 *
 * will get rewritten to:
 *
 * ```scala
 * <label> def foo(i: Int) = dough(i)
 * <label> def dough(i: Int) = if (i == 0) bar else foo(i-1)
 * <label> def bar = 2
 *   foo(100)
 * ```
 *
 * Proposed way to generate this pattern in backend is:
 *
 * ```scala
 * foo(100)
 * <jump foo>
 * <label> def foo(i: Int) = dough(i)
 * // <jump a>                           // unreachable
 * <label> def dough(i: Int) = if (i == 0) bar else foo(i-1)
 * // <jump a>                           // unreachable
 * <label> def bar = 2
 * // <jump a>                           // unreachable
 * <asm point a>
 * ```
 *
 * Unreachable jumps will be eliminated by local dead code analysis.
 * After JVM is smart enough to remove next-line jumps
 *
 * Note that his phase Ychecking this phase required softening scoping rules
 * as it intentionally allowed to break scoping rules inside methods for labels.
 * This is modified by setting `labelsReordered` flag in Phases.
 *
 * @author Dmitry Petrashko
 */
class LabelDefs extends MiniPhase {
  import tpd._

  def phaseName: String = "labelDef"

  override def transformDefDef(tree: DefDef)(implicit ctx: Context): Tree = {
    if (tree.symbol is Label) tree
    else {
      val labelDefs = collectLabelDefs(tree.rhs)

      def putLabelDefsNearCallees = new TreeMap() {
        override def transform(tree: Tree)(implicit ctx: Context): Tree = {
          tree match {
            case t: Apply if labelDefs.contains(t.symbol) =>
              val labelDef = labelDefs(t.symbol)
              labelDefs -= t.symbol
              val labelDef2 = cpy.DefDef(labelDef)(rhs = transform(labelDef.rhs))
              Block(labelDef2:: Nil, t)
            case t: DefDef =>
              assert(t.symbol is Label)
              EmptyTree
            case _ => if (labelDefs.nonEmpty) super.transform(tree) else tree
          }
        }
      }

      if (labelDefs.isEmpty) tree
      else cpy.DefDef(tree)(rhs = putLabelDefsNearCallees.transform(tree.rhs))
    }
  }

  private def collectLabelDefs(tree: Tree)(implicit ctx: Context): mutable.HashMap[Symbol, DefDef] = {
    // labelSymbol -> Defining tree
    val labelDefs = new mutable.HashMap[Symbol, DefDef]()
    new TreeTraverser {
      override def traverse(tree: Tree)(implicit ctx: Context): Unit = tree match {
        case t: DefDef =>
          assert(t.symbol is Label)
          labelDefs(t.symbol) = t
          traverseChildren(t)
        case _ => traverseChildren(tree)
      }
    }.traverse(tree)
    labelDefs
  }
}

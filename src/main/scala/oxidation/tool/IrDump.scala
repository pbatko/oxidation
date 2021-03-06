package oxidation
package tool

import java.io.{DataOutputStream, File, FileOutputStream, OutputStream, OutputStreamWriter, PrintWriter}

import analyze._
import parse.Parser

import scala.io.Source
import cats._
import cats.data._
import cats.implicits._
import ir.serialization.Serialize
import codegen.pass.Pass
import codegen.{Codegen, Name, pass}
import oxidation.backend.amd64.Amd64BackendPass
import oxidation.backend.shared.{BlockLinearizationPass, FlowGraph, RegisterLifetime}
import oxidation.ir.{Block, ConstantPoolEntry, Register}

object IrDump extends App {

  case class Options(infiles: Seq[File] = Seq.empty,
                     passes: List[Pass] = List.empty,
                     timing: Boolean = false,
                     out: Option[File] = None,
                     format: Format = Textual,
                     dumpAfterEachPass: Boolean = false,
                     showLifetimes: Boolean = false) extends LogOptions

  sealed trait Format
  case object Textual extends Format
  case object Binary extends Format


  val AllPasses: List[Pass] = List(
    pass.ArrInit,
    pass.ExplicitBlocks,
    pass.DeadBlockRemoval,
    pass.ValInterpretPass,
    pass.ConstantInlining,
    pass.EnumLoweringPass,
    pass.StructLowering,
    pass.UnitRemoval,
    pass.ConstantRemoval,
    pass.ExprWeaken,
    pass.ssa.IntoSSA,
    pass.ssa.ConstantPropagation,
    pass.ssa.UnusedRegRemoval,
    pass.ssa.FromSSA,
    pass.ArrayDealiasing,
    Amd64BackendPass,
    BlockLinearizationPass
  )

  implicit val passRead: scopt.Read[Pass] =
    scopt.Read.reads(n => AllPasses.find(_.name == n)
      .getOrElse(throw new IllegalArgumentException(s"No pass named $n")))

  val optParser = new scopt.OptionParser[Options]("astdump") {

    head("irdump", "Compiles a program and prints the resulting intermediate representation")

    opt[Unit]('v', "verbose")
      .action((_, o) => o.copy(timing = true))

    arg[File]("infiles")
      .required().unbounded()
      .action((f, o) => o.copy(infiles = o.infiles :+ f))

    def upTo(p: Pass): List[Pass] = AllPasses.reverse.dropWhile(_ != p).reverse

    opt[Pass]('p', "passupto")
      .action((p, o) => o.copy(passes = upTo(p)))

    opt[File]('o', "outfile")
      .action((f, o) => o.copy(out = Some(f)))

    opt[Unit]('b', "binary")
      .action((_, o) => o.copy(format = Binary))

    opt[Unit]('a', "dump-after-each-pass")
      .action((_, o) => o.copy(dumpAfterEachPass = true))

    opt[Unit]('l', "show-lifetimes")
      .action((_, o) => o.copy(showLifetimes = true))

  }

  def get[L, R](e: Either[L, R]): R = e.fold(l => {
    Console.err.println(l)
    sys.exit(1)
  }, identity)

  optParser.parse(args, Options()).foreach { implicit options =>
    val parsed = phase("parse") {
      options.infiles.map { f =>
        val parser = new Parser(Some(f.getName))
        parser.compilationUnit.parse(Source.fromFile(f).mkString).get.value
      }
    }
    val symbols = phase("symbol-search") {
      parsed.toVector.foldMap(tlds => get(SymbolSearch.findSymbols(tlds)))
    }
    val scope = BuiltinSymbols.symbols |+| symbols
    val resolvedSymbols = phase("symbol-resolve") {
      parsed.toVector.flatMap(tlds => get(SymbolResolver.resolveSymbols(tlds, scope)))
    }

    val typeDefs = resolvedSymbols.collect {
      case d: parse.ast.TypeDef => d
    }
    val ctxt = phase("type-interpret") {
      get(TypeInterpreter.solveTree(typeDefs, Ctxt.default))
    }
    val termDefs = resolvedSymbols.collect {
      case d: parse.ast.TermDef => d
    }
    val deps = phase("dependency-graph") {
      DependencyGraph.build(resolvedSymbols).prune
    }
    val typed = phase("type") {
      get(TypeTraverse.solveTree(deps, termDefs, ctxt).toEither)
    }

    val irDefs = phase("codegen") {
      typed.map {
        case d: analyze.ast.TermDef => Codegen.compileDef(d)
      }.toVector
    }

    def fileAfterPhase(out: File, phase: String): File = {
      val (name, ext) = out.getName.splitAt(out.getName.lastIndexOf('.'))
      new File(out.getParentFile, s"$name-$phase$ext")
    }

    def dumpAfter(phase: String, defs: Vector[ir.Def]): Unit = options.out match {
      case None =>
        println(s"--- Ir after phase $phase ---")
        dump(defs, System.out, options)
      case Some(f) =>
        val out = fileAfterPhase(f, phase)
        dump(defs, new FileOutputStream(out), options)
    }

    if(options.dumpAfterEachPass) {
      dumpAfter("codegen", irDefs)
    }
    val passed = options.passes.foldLeft(irDefs.toVector) { (defs, pass) =>
      val res = pass.extract(pass.txDefs(defs))
      if(options.dumpAfterEachPass) {
        dumpAfter(pass.name, res)
      }
      res
    }
    if(!options.dumpAfterEachPass) {
      options.out match {
        case None =>
          dump(passed, System.out, options)
        case Some(f) =>
          dump(passed, new FileOutputStream(f), options)
      }
    }
  }

  def dump(defs: Vector[ir.Def], out: OutputStream, opts: Options): Unit = opts.format match {
    case Textual =>
      val writer = new PrintWriter(out, true)
      defs.foreach {
        case fun @ ir.Def.Fun(name, params, ret, body, constants) =>
          writer.println(show"def $name(${params.map(_.show).mkString(", ")}): $ret {")
          dumpFunLike(body, constants, writer, opts)

        case ir.Def.ComputedVal(name, body, typ, constants) =>
          writer.println(show"val $name: $typ {")
          dumpFunLike(body, constants, writer, opts)

        case ir.Def.ExternFun(name, params, ret) =>
          writer.println(show"def $name(${params.map(_.show).mkString(", ")}): $ret = extern")

        case ir.Def.TrivialVal(name, value, mutability) =>
          val prefix = mutability match {
            case ir.Def.Mutable => "var"
            case ir.Def.Immutable => "val"
          }
          writer.println(show"$prefix $name = $value")
      }

    case Binary =>
      val s = new Serialize(new DataOutputStream(out))
      s.writeDefs(defs.toSeq)
  }

  def dumpFunLike(body: Vector[Block], constants: Set[ConstantPoolEntry], writer: PrintWriter, opts: Options): Unit = {
    lazy val flowGraph = FlowGraph(body)
    lazy val inputs = body.map(b => b.name -> RegisterLifetime.inputs(b)).toMap
    lazy val outputs = body.map(b => b.name -> RegisterLifetime.outputs(flowGraph, b.name, inputs)).toMap
    lazy val ghosts = body.map(b => b.name -> RegisterLifetime.ghosts(flowGraph, b.name, inputs, outputs)).toMap
    if(constants.nonEmpty) {
      writer.println("  constants {")
      constants.foreach { e =>
        writer.println(show"    $e")
      }
      writer.println("  }")
    }
    body.foreach { block =>
      writer.println(show"  ${block.name} {")
      if(opts.showLifetimes) {
        writer.println(show"    #live in: ${inputs(block.name).map(_.show) mkString ", "}")
        writer.println(show"    #ghost in: ${ghosts(block.name).map(_.show) mkString ", "}")
      }
      block.instructions.foreach { instr =>
        writer.println(show"    $instr")
      }
      writer.println(show"    ${block.flow}")
      if(opts.showLifetimes) {
        writer.println(show"    #live out: ${outputs(block.name).map(_.show) mkString ", "}")
        writer.println(show"    #ghost out: ${ghosts(block.name).map(_.show) mkString ", "}")
      }
      writer.println("  }")
    }
    writer.println("}")
  }

}

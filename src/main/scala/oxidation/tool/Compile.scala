package oxidation
package tool

import java.io.{BufferedWriter, File, OutputStreamWriter}

import oxidation.analyze._
import oxidation.parse.Parser

import scala.io.Source
import cats._
import cats.data._
import cats.implicits._
import oxidation.backend.amd64.{Amd64NasmOutput, Amd64Target}
import oxidation.codegen.Codegen

object Compile extends App {

  case class Options(infiles: Seq[File] = Seq.empty, verbose: Boolean = false) extends LogOptions

  val optParser = new scopt.OptionParser[Options]("astdump") {

    head("irdump", "Compiles a program and prints the resulting intermediate representation")

    opt[Unit]('v', "verbose")
      .action((_, o) => o.copy(verbose = true))

    arg[File]("infiles")
      .required().unbounded()
      .action((f, o) => o.copy(infiles = o.infiles :+ f))

  }

  def get[L, R](e: Either[L, R]): R = e.fold(l => {
    Console.err.println(l)
    sys.exit(1)
  }, identity)

  optParser.parse(args, Options()).foreach { implicit options =>
    val parser = new Parser
    val parsed = phase("parse") {
      options.infiles.map(f => parser.compilationUnit.parse(Source.fromFile(f).mkString).get.value)
    }
    val symbols = phase("symbol-search") {
      parsed.toVector.foldMap(tlds => get(SymbolSearch.findSymbols(tlds.toVector)))
    }
    val scope = BuiltinSymbols.symbols |+| symbols
    val resolvedSymbols = phase("symbol-resolve") {
      parsed.toVector.flatMap(tlds => get(SymbolResolver.resolveSymbols(tlds.toVector, scope)))
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
      DependencyGraph.build(termDefs).prune
    }
    val typed = phase("type") {
      get(TypeTraverse.solveTree(deps, termDefs, ctxt))
    }

    val irDefs = phase("codegen") {
      typed.map {
        case d: analyze.ast.TermDef => Codegen.compileDef(d)
      }
    }
    phase("asm-out") {
      val target = new Amd64Target with Amd64NasmOutput
      irDefs.foldMap(target.outputDef).foreach(println)
    }
  }

}
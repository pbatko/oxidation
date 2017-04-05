package oxidation
package tool

import java.io.{BufferedWriter, File, FileWriter, OutputStreamWriter}

import oxidation.analyze._
import oxidation.parse.Parser

import scala.io.Source
import cats._
import cats.data._
import cats.implicits._
import oxidation.backend.amd64.{Amd64NasmOutput, Amd64Target}
import oxidation.codegen.{Codegen, Name, pass}
import oxidation.codegen.pass.Pass
import oxidation.ir.ConstantPoolEntry
import oxidation.ir.validation.{ValidationError, Validator}

import scala.sys.process.Process

object Compile {

  case class Options(infiles: Seq[File] = Seq.empty,
                     validate: Boolean = false,
                     timing: Boolean = false) extends LogOptions

  val optParser = new scopt.OptionParser[Options]("oxc") {

    head("oxc", "Compiles a program and outputs assembly")

    opt[Unit]('t', "timing")
      .action((_, o) => o.copy(timing = true))

    opt[Unit]('v', "validate")
      .action((_, o) => o.copy(validate = true))

    arg[File]("infiles")
      .required().unbounded()
      .action((f, o) => o.copy(infiles = o.infiles :+ f))

  }

  final case class ParseError(message: String) extends CompileError
  final case class ValidatorError(err: ValidationError, after: String) extends CompileError
  case object AssemblerError extends CompileError
  case object LinkerError extends CompileError

  def get[L, R](e: Either[L, R]): R = e.fold(l => {
    Console.err.println(l)
    sys.exit(1)
  }, identity)

  def compile(implicit options: Options): Either[CompileError, File] = {
    val parser = new Parser
    for {
      parsed <- options.infiles.toVector.traverse { f =>
        val res = parser.compilationUnit.parse(Source.fromFile(f).mkString)
        res.fold(
          (_, _, _) => Left(ParseError(res.toString)),
          (r, _) => Right(r)
        )
      }
      symbols <- parsed.traverse(SymbolSearch.findSymbols).map(_.combineAll)
      scope = BuiltinSymbols.symbols |+| symbols
      resolvedSymbols <- parsed.traverse(SymbolResolver.resolveSymbols(_, scope)).map(_.flatten)
      typeDefs = resolvedSymbols.collect {
        case d: parse.ast.TypeDef => d
      }
      ctxt <- TypeInterpreter.solveTree(typeDefs, Ctxt.default)
      termDefs = resolvedSymbols.collect {
        case d: parse.ast.TermDef => d
      }
      deps = DependencyGraph.build(termDefs).prune
      typed <- TypeTraverse.solveTree(deps, termDefs, ctxt)
      irDefs = typed.map {
        case d: analyze.ast.TermDef => Codegen.compileDef(d)
      }
      _ <-
        if(options.validate)
          irDefs.traverse_(Validator.validateDef).left.map(ValidatorError(_, "codegen"))
        else Right(())
      passes: List[Pass] = List(
        pass.ExplicitBlocks,
        pass.StructLowering
      )
      passed <- passes.foldLeft(Right(irDefs): Either[CompileError, Set[ir.Def]]) { (defs, pass) =>
        defs.flatMap { defs =>
          val d = defs.flatMap(d => pass.extract(pass.txDef(d)))
          if(options.validate)
            d.traverse_(Validator.validateDef).left.map(ValidatorError(_, s"pass ${pass.name}")).as(d)
          else Right(d)
        }
      }
      constants: Set[ConstantPoolEntry] = passed.flatMap {
        case ir.Def.Fun(_, _, _, _, c) => c
        case _ => Set.empty[ConstantPoolEntry]
      }
      namedConstants = constants.toVector.zipWithIndex.map {
        case (c, i) => c -> Name.Global(List("$const", i.toString))
      }.toMap

      target = new Amd64Target with Amd64NasmOutput
      baseInFile = options.infiles.head
      baseName = baseInFile.getName.split("\\.").head
      targetFolder = new File(baseInFile.getParentFile, "target")
      _ = targetFolder.mkdir()
      asmFile = new File(targetFolder, baseName + ".asm")
      objFile = new File(targetFolder, baseName + ".obj")
      exeFile = new File(targetFolder, baseName + ".exe")
      out = new FileWriter(asmFile)
      _ = {
        target.outputDefs(passed.toVector)(namedConstants).foreach { l =>
          out.write(l)
          out.write('\n')
        }
        target.outputConstants(namedConstants).foreach { l =>
          out.write(l)
          out.write('\n')
        }
        out.close()
      }
      _ <- Either.cond(Process(Seq("nasm", "-fwin64", "-o", objFile.getAbsolutePath, asmFile.getAbsolutePath)).! == 0,
        (), AssemblerError)
      _ <- Either.cond(Process(Seq("gcc", objFile.getAbsolutePath, "-Wl,-emain", "-o", exeFile.getAbsolutePath)).! == 0,
        (), LinkerError)
    } yield exeFile
  }

  def main(args: Array[String]): Unit = {
    optParser.parse(args, Options()).foreach(compile(_))
  }


}

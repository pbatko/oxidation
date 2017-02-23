package oxidation
package parse

import fastparse.noApi._
import fastparse.WhitespaceApi
import oxidation.ast._
import oxidation.FunctorOps
import sourcecode.Name

//noinspection ForwardReference
class Parser {

  val WS: P0 = {
    import fastparse.all._
    CharsWhile(_.isWhitespace, min = 0)
  }
  val WSNoNL: P0 = {
    import fastparse.all._
    CharsWhile(c => c.isWhitespace && !"\r\n".contains(c), min = 0) ~ !CharPred("\r\n".contains(_))
  }

  val White: WhitespaceApi.Wrapper = WhitespaceApi.Wrapper(WS)
  import White._

  implicit val PFunctor: Functor[P] = new Functor[P] {
    override def map[A, B](a: P[A], f: A => B): P[B] = parserApi(a).map(f)
  }

  def whole[A](p: P[A]): P[A] = p ~ End

  private val expression0: P[Expression] = P(
    intLiteral | varacc | parexp | blockexpr | ifexp | whileexp
  )
  private val expression1: P[Expression] = P(
    expression0 ~~ postfix.repX
  ).map {
    case (exp, postfixes) => postfixes.foldLeft(exp)((e, f) => f(e))
  }
  private val expression2: P[Expression] = P(
    prefixOp.? ~ expression1
  ).map {
    case (Some(op), expr) => PrefixAp(op, expr)
    case (None, expr) => expr
  }
  private val expression3: P[Expression] = infixl(expression2, op3)
  private val expression4: P[Expression] = infixl(expression3, op4)
  private val expression5: P[Expression] = infixl(expression4, op5)
  private val expression6: P[Expression] = infixl(expression5, op6)
  private val expression7: P[Expression] = infixl(expression6, op7)
  private val expression8: P[Expression] =
    P(expression7 ~ (assignOp ~ expression).?).map {
      case (e, None) => e
      case (lval, Some((op, rval))) => Assign(lval, op, rval)
    }

  val expression: P[Expression] = P(expression8)

  val definition: P[Def] = P(
    defdef | valdef | vardef | structdef
  )

  type Postfix = Expression => Expression
  private val postfix: P[Postfix] =
    P(apply | select)
  private val apply: P[Expression => Apply] =
    P(WSNoNL ~~ "(" ~/ expression.rep(sep = ",") ~ ")").map(params => Apply(_, params))
  private val select: P[Expression => Select] =
    P(WS ~~ "." ~/ id.!).map(id => Select(_, id))

  private val semi: P0 =
    P(";" | "\n" | "\r" | "\r\n")

  private def infixl(exp: => P[Expression], op: => P[InfixOp]): P[Expression] = P(
    exp ~ (op ~/ exp).rep
  ).map { case (head, bs) =>
    bs.foldLeft(head) { case (a, (op, b)) => InfixAp(op, a, b) }
  }

  private def K(p: String): P0 = p ~~ !CharPred(c => c.isLetterOrDigit || idSpecialChars.contains(c))
  private def O(p: String): P0 = p ~~ !CharIn("~!%^&*+=<>|/?")

  val keyword: P0 = P(Seq("if", "else", "def", "while", "struct", "enum", "val", "var").map(K).reduce(_ | _))

  private val idSpecialChars = "$_"
  private val idStart = CharPred(c => c.isLetter || idSpecialChars.contains(c))
  private val id: P0 = {
    val idRest  = CharsWhile(c => c.isLetterOrDigit || idSpecialChars.contains(c), min = 0)
    !keyword ~ idStart ~ idRest
  }

  private val typ: P[Type] = P(
    id.!.map(Type.Named)
  )(Name("type"))

  private val defdef: P[DefDef] = {
    val param = (id.! ~ ":" ~ typ).map(Param.tupled)
    val paramList = "(" ~ param.rep ~ ")"

    P(K("def") ~/ id.! ~ paramList.? ~ (":" ~/ typ).? ~ "=" ~ expression).map(DefDef.tupled)
  }

  private val defBody: P[(String, Option[Type], Expression)] =
    id.! ~ (":" ~ typ).? ~ O("=") ~/ expression

  private val valdef: P[ValDef] =
    P(K("val") ~/ defBody).map(ValDef.tupled)
  private val vardef: P[VarDef] =
    P(K("var") ~/ defBody).map(VarDef.tupled)

  private val structdef: P[StructDef] = {
    val member = (WS ~~ id.! ~~ WSNoNL ~~ ":" ~ typ).map(StructMember.tupled)
    val body = "{" ~~ member.repX(sep = semi) ~ "}"
    P(K("struct") ~/ id.! ~ O("=") ~ body).map(StructDef.tupled)
  }

  private val intLiteral: P[IntLit] =
    P(hexInt | decimalInt).map(IntLit)
  private val decimalInt: P[Int] =
    CharsWhile(_.isDigit).!.map(_.toInt)
  private val hexInt: P[Int] =
    ("0x" ~/ CharsWhile(c => c.isDigit || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')).!)
      .map(Integer.parseInt(_, 16))

  private val varacc: P[Var] =
    P(id.!).map(Var)

  private val parexp: P[Expression] =
    P("(" ~/ expression ~ ")")

  private val blockexpr: P[Block] =
    P("{" ~/ (expression | definition).repX(sep = semi) ~ "}").map(Block)

  private val ifexp: P[If] = {
    val els = P(K("else") ~/ expression)
    P(K("if") ~/ "(" ~ expression ~ ")" ~/ expression ~ els.?).map(If.tupled)
  }

  private val whileexp: P[While] =
    P(K("while") ~/ "(" ~ expression ~ ")" ~/ expression).map(While.tupled)

  private val prefixOp: P[PrefixOp] = P(
    O("-").mapTo(PrefixOp.Neg)
  | O("!").mapTo(PrefixOp.Not)
  | O("~").mapTo(PrefixOp.Inv)
  )

  private val assignOp: P[Option[InfixOp]] = P(
    O("=").mapTo(None)
  | O("+=").mapTo(Some(InfixOp.Add))
  | O("-=").mapTo(Some(InfixOp.Sub))
  | O("*=").mapTo(Some(InfixOp.Mul))
  | O("/=").mapTo(Some(InfixOp.Div))
  | O("%=").mapTo(Some(InfixOp.Mod))
  | O("<<=").mapTo(Some(InfixOp.Shl))
  | O(">>=").mapTo(Some(InfixOp.Shr))
  | O("^=").mapTo(Some(InfixOp.Xor))
  | O("&=").mapTo(Some(InfixOp.And))
  | O("|=").mapTo(Some(InfixOp.Or))
  )

  private val op3: P[InfixOp] = P(
    O("*").mapTo(InfixOp.Mul)
  | O("/").mapTo(InfixOp.Div)
  | O("%").mapTo(InfixOp.Mod)
  )
  private val op4: P[InfixOp] = P(
    O("+").mapTo(InfixOp.Add)
  | O("-").mapTo(InfixOp.Sub)
  )
  private val op5: P[InfixOp] = P(
    O(">>").mapTo(InfixOp.Shr)
  | O("<<").mapTo(InfixOp.Shl)
  )
  private val op6: P[InfixOp] = P(
    O(">=").mapTo(InfixOp.Geq)
  | O(">") .mapTo(InfixOp.Gt)
  | O("<=").mapTo(InfixOp.Leq)
  | O("<") .mapTo(InfixOp.Lt)
  )
  private val op7: P[InfixOp] = P(
    O("==").mapTo(InfixOp.Eq)
  | O("!=").mapTo(InfixOp.Neq)
  )

}

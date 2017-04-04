package oxidation
package ir
package validation

import cats._
import cats.data._
import cats.implicits._

import oxidation.codegen.{Codegen, Name}
import Type._
import utest._

object ValidatorTests extends TestSuite with IrValSyntax {

  import Validator._
  import Codegen.{ register => r }

  val tests = apply {
    "validateInstruction" - {
      val loc = Location(Name.Global(List("foo")), Name.Local("body", 0), 0)
      "Copy" - {
        "valid" - {
          validateInstruction(loc, Inst.Move(r(0, I32), Op.Copy(Val.I(10, I32))))
            .value.runEmptyA.value ==> Right(())
        }
        "invalid" - {
          validateInstruction(loc, Inst.Move(r(0, I32), Op.Copy(Val.I(10, U32))))
            .value.runEmptyA.value ==> Left(ValidationError.WrongType(loc, I32, U32))
        }
      }
      "Widen" - {
        "valid" - {
          validateInstruction(loc, Inst.Move(r(0, I32), Op.Widen(Val.I(10, I8))))
            .value.runEmptyA.value ==> Right(())
        }
        "invalid" - {
          validateInstruction(loc, Inst.Move(r(0, I32), Op.Widen(Val.Struct(Vector.empty))))
            .value.runEmptyA.value ==> Left(ValidationError.NotANumericType(loc, Struct(Vector.empty)))
        }
      }
      "Trim" - {
        "valid" - {
          validateInstruction(loc, Inst.Move(r(0, U8), Op.Trim(r(1, U32))))
            .value.runA(Set(r(0, U8), r(1, U32))).value ==> Right(())
        }
        "invalid" - {
          validateInstruction(loc, Inst.Move(r(0, Struct(Vector(I32))), Op.Trim(r(1, U32))))
            .value.runA(Set(r(0, U8), r(1, U32))).value ==> Left(ValidationError.NotANumericType(loc, Struct(Vector(I32))))
        }
      }
      "Garbled" - {
        "valid" - {
          validateInstruction(loc, Inst.Move(r(0, I32), Op.Garbled))
            .value.runEmptyA.value ==> Right(())
        }
      }
      "Call" - {
        "valid" - {
          validateInstruction(loc, Inst.Move(r(1, I32), Op.Call(
            Val.G(Name.Global(List("foo")), Fun(List(I64), I32)),
            List(r(0, I64))
          ))).value.runA(Set(r(0, I64))).value ==> Right(())
        }
        "invalid" - {
          "wrong return type" - {
            validateInstruction(loc, Inst.Move(r(0, I64), Op.Call(
              Val.G(Name.Global(List("foo")), Fun(Nil, I32)), Nil
            ))).value.runEmptyA.value ==> Left(ValidationError.WrongType(loc, I64, I32))
          }
          "wrong number of parameters" - {
            validateInstruction(loc, Inst.Move(r(0, I32), Op.Call(
              Val.G(Name.Global(List("foo")), Fun(List(I64), I32)), Nil
            ))).value.runEmptyA.value ==> Left(ValidationError.WrongArity(loc, 1, 0))
          }
          "not a function" - {
            validateInstruction(loc, Inst.Move(r(0, I32), Op.Call(
              Val.I(32, I32), Nil
            ))).value.runEmptyA.value ==> Left(ValidationError.NotAFunction(loc, I32))
          }
        }
      }
      "Member" - {
        val structType = Struct(Vector(I32, I64, U1))
        val r0 = r(0, structType)
        "valid" - {
          validateInstruction(loc, Inst.Move(r(1, I32), Op.Member(r0, 0)))
            .value.runA(Set(r0)).value ==> Right(())
        }
        "invalid" - {
          "wrong type" - {
            validateInstruction(loc, Inst.Move(r(1, I32), Op.Member(r0, 2)))
              .value.runA(Set(r0)).value ==> Left(ValidationError.WrongType(loc, I32, U1))
          }
          "member index out of bounds" - {
            validateInstruction(loc, Inst.Move(r(1, I32), Op.Member(r0, 5)))
              .value.runA(Set(r0)).value ==> Left(ValidationError.StructMemberOutOfBounds(loc, structType, 5))
          }
          "not a struct" - {
            validateInstruction(loc, Inst.Move(r(1, I32), Op.Member(r(0, I32), 5)))
              .value.runA(Set(r(0, I32))).value ==> Left(ValidationError.NotAStruct(loc, I32))
          }
        }
      }
      "Load" - {
        "valid" - {
          validateInstruction(loc, Inst.Move(r(2, I32), Op.Load(r(0, Ptr), r(1, I64))))
            .value.runA(Set(r(0, Ptr), r(1, I64))).value ==> Right(())
        }
        "invalid" - {
          "addr is not a pointer" - {
            validateInstruction(loc, Inst.Move(r(2, I32), Op.Load(r(0, U1), r(1, I64))))
              .value.runA(Set(r(0, U1), r(1, I64))).value ==> Left(ValidationError.WrongType(loc, Ptr, U1))
          }
          "offset is not I64" - {
            validateInstruction(loc, Inst.Move(r(2, I32), Op.Load(r(0, Ptr), r(1, U0))))
              .value.runA(Set(r(0, Ptr), r(1, U0))).value ==> Left(ValidationError.WrongType(loc, I64, U0))
          }
        }
      }
      "Store" - {
        "valid" - {
          validateInstruction(loc, Inst.Do(Op.Store(r(0, Ptr), r(1, I64), r(2, I32))))
            .value.runA(Set(r(0, Ptr), r(1, I64), r(2, I32))).value ==> Right(())
        }
        "invalid" - {
          "move to wrong type" - {
            validateInstruction(loc, Inst.Move(r(3, I32), Op.Store(r(0, Ptr), r(1, I64), r(2, I32))))
              .value.runA(Set(r(0, Ptr), r(1, I64), r(2, I32))).value ==> Left(ValidationError.WrongType(loc, I32, U0))
          }
          "addr is not a pointer" - {
            validateInstruction(loc, Inst.Do(Op.Store(r(0, U0), r(1, I64), r(2, I32))))
              .value.runA(Set(r(0, U0), r(1, I64), r(2, I32))).value ==> Left(ValidationError.WrongType(loc, Ptr, U0))
          }
          "offset is not I64" - {
            validateInstruction(loc, Inst.Do(Op.Store(r(0, Ptr), r(1, U0), r(2, I32))))
              .value.runA(Set(r(0, Ptr), r(1, U0), r(2, I32))).value ==> Left(ValidationError.WrongType(loc, I64, U0))
          }
        }
      }
      "Unary" - {
        "Neg" - {
          "valid" - {
            validateInstruction(loc, Inst.Move(r(1, I32), Op.Unary(PrefixOp.Neg, r(0, I32))))
              .value.runA(Set(r(0, I32))).value ==> Right(())
          }
          "invalid" - {
            "wrong type" - {
              validateInstruction(loc, Inst.Move(r(1, I64), Op.Unary(PrefixOp.Neg, r(0, I32))))
                .value.runA(Set(r(0, I32))).value ==> Left(ValidationError.WrongType(loc, I64, I32))
            }
            "not a numeric type" - {
              validateInstruction(loc, Inst.Move(r(1, Ptr), Op.Unary(PrefixOp.Neg, r(0, Ptr))))
                .value.runA(Set(r(0, Ptr))).value ==> Left(ValidationError.NotANumericType(loc, Ptr))
            }
          }
        }
        "Not" - {
          "valid" - {
            validateInstruction(loc, Inst.Move(r(1, U1), Op.Unary(PrefixOp.Not, r(0, U1))))
              .value.runA(Set(r(0, U1))).value ==> Right(())
          }
          "invalid" - {
            "wrong source type" - {
              validateInstruction(loc, Inst.Move(r(1, U1), Op.Unary(PrefixOp.Not, r(0, I32))))
                .value.runA(Set(r(0, I32))).value ==> Left(ValidationError.WrongType(loc, U1, I32))
            }
            "wrong dest type" - {
              validateInstruction(loc, Inst.Move(r(1, I32), Op.Unary(PrefixOp.Not, r(0, U1))))
                .value.runA(Set(r(0, U1))).value ==> Left(ValidationError.WrongType(loc, I32, U1))
            }
          }
        }
      }
      "Arith" - {
        "Arithmetic" - {
          "valid" - {
            validateInstruction(loc, Inst.Move(r(2, I32), Op.Arith(InfixOp.Add, r(0, I32), r(1, I32))))
              .value.runA(Set(r(0, I32), r(1, I32))).value ==> Right(())
          }
          "invalid" - {
            "not numeric" - {
              validateInstruction(loc, Inst.Move(r(2, I32), Op.Arith(InfixOp.Add, r(0, U1), r(1, U1))))
                .value.runA(Set(r(0, U1), r(1, U1))).value ==> Left(ValidationError.NotANumericType(loc, U1))
            }
            "different operand types" - {
              validateInstruction(loc, Inst.Move(r(2, I32), Op.Arith(InfixOp.Add, r(0, I32), r(1, I64))))
                .value.runA(Set(r(0, I32), r(1, I64))).value ==> Left(ValidationError.WrongType(loc, I32, I64))
            }
            "wrong dest type" - {
              validateInstruction(loc, Inst.Move(r(2, I64), Op.Arith(InfixOp.Add, r(0, I32), r(1, I32))))
                .value.runA(Set(r(0, I32), r(1, I32))).value ==> Left(ValidationError.WrongType(loc, I64, I32))
            }
          }
        }
        "Comparison" - {
          "valid" - {
            validateInstruction(loc, Inst.Move(r(2, U1), Op.Arith(InfixOp.Lt, r(0, I32), r(1, I32))))
              .value.runA(Set(r(0, I32), r(1, I32))).value ==> Right(())
          }
          "invalid" - {
            "not numeric" - {
              validateInstruction(loc, Inst.Move(r(2, U1), Op.Arith(InfixOp.Lt, r(0, U1), r(1, U1))))
                .value.runA(Set(r(0, U1), r(1, U1))).value ==> Left(ValidationError.NotANumericType(loc, U1))
            }
            "different operand types" - {
              validateInstruction(loc, Inst.Move(r(2, U1), Op.Arith(InfixOp.Lt, r(0, I32), r(1, I64))))
                .value.runA(Set(r(0, I32), r(1, I64))).value ==> Left(ValidationError.WrongType(loc, I32, I64))
            }
            "wrong dest type" - {
              validateInstruction(loc, Inst.Move(r(2, Ptr), Op.Arith(InfixOp.Lt, r(0, I32), r(1, I32))))
                .value.runA(Set(r(0, I32), r(1, I32))).value ==> Left(ValidationError.WrongType(loc, Ptr, U1))
            }
          }
        }
        "Equality" - {
          "valid" - {
            validateInstruction(loc, Inst.Move(r(2, U1), Op.Arith(InfixOp.Eq, r(0, I32), r(1, I32))))
              .value.runA(Set(r(0, I32), r(1, I32))).value ==> Right(())
          }
          "invalid" - {
            "different operand types" - {
              validateInstruction(loc, Inst.Move(r(2, U1), Op.Arith(InfixOp.Eq, r(0, I32), r(1, I64))))
                .value.runA(Set(r(0, I32), r(1, I64))).value ==> Left(ValidationError.WrongType(loc, I32, I64))
            }
            "wrong dest type" - {
              validateInstruction(loc, Inst.Move(r(2, Ptr), Op.Arith(InfixOp.Eq, r(0, I32), r(1, I32))))
                .value.runA(Set(r(0, I32), r(1, I32))).value ==> Left(ValidationError.WrongType(loc, Ptr, U1))
            }
          }
        }
        "Bit" - {
          "valid" - {
            "numeric" - {
              validateInstruction(loc, Inst.Move(r(2, I32), Op.Arith(InfixOp.Xor, r(0, I32), r(1, I32))))
                .value.runA(Set(r(0, I32), r(1, I32))).value ==> Right(())
            }
            "boolean" - {
              validateInstruction(loc, Inst.Move(r(2, U1), Op.Arith(InfixOp.Xor, r(0, U1), r(1, U1))))
                .value.runA(Set(r(0, U1), r(1, U1))).value ==> Right(())
            }
          }
          "invalid" - {
            "not numeric or boolean" - {
              validateInstruction(loc, Inst.Move(r(2, Ptr), Op.Arith(InfixOp.Xor, r(0, Ptr), r(1, Ptr))))
                .value.runA(Set(r(0, Ptr), r(1, Ptr))).value ==> Left(ValidationError.NotANumericType(loc, Ptr))
            }
            "different operand types" - {
              validateInstruction(loc, Inst.Move(r(2, I32), Op.Arith(InfixOp.BitAnd, r(0, I32), r(1, I64))))
                .value.runA(Set(r(0, I32), r(1, I64))).value ==> Left(ValidationError.WrongType(loc, I32, I64))
            }
            "wrong dest type" - {
              validateInstruction(loc, Inst.Move(r(2, Ptr), Op.Arith(InfixOp.BitOr, r(0, I32), r(1, I32))))
                .value.runA(Set(r(0, I32), r(1, I32))).value ==> Left(ValidationError.WrongType(loc, Ptr, I32))
            }
          }
        }
      }
    }
  }

}

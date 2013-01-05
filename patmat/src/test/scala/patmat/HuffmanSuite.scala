package patmat

import org.scalatest.FunSuite

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import patmat.Huffman._

@RunWith(classOf[JUnitRunner])
class HuffmanSuite extends FunSuite {
  trait TestTrees {
    val t1 = Fork(Leaf('a', 2), Leaf('b', 3), List('a', 'b'), 5)
    val t2 = Fork(Fork(Leaf('a', 2), Leaf('b', 3), List('a', 'b'), 5), Leaf('d', 4), List('a', 'b', 'd'), 9)
  }

  test("weight of a larger tree") {
    new TestTrees {
      assert(weight(t1) === 5)
    }
  }

  test("chars of a larger tree") {
    new TestTrees {
      assert(chars(t2) === List('a', 'b', 'd'))
    }
  }

  test("string2chars(\"hello, world\")") {
    assert(string2Chars("hello, world") === List('h', 'e', 'l', 'l', 'o', ',', ' ', 'w', 'o', 'r', 'l', 'd'))
  }

  test("makeOrderedLeafList for some frequency table") {
    assert(makeOrderedLeafList(List(('t', 2), ('e', 1), ('x', 3))) === List(Leaf('e', 1), Leaf('t', 2), Leaf('x', 3)))
  }

  test("combine of some leaf list") {
    val leaflist = List(Leaf('e', 1), Leaf('t', 2), Leaf('x', 4))
    assert(combine(leaflist) === List(Fork(Leaf('e', 1), Leaf('t', 2), List('e', 't'), 3), Leaf('x', 4)))
  }

  test("decode and encode a very short text should be identity") {
    new TestTrees {
      assert(decode(t1, encode(t1)("ab".toList)) === "ab".toList)
    }
  }

  test("decoded secret") {
    assert(decodedSecret === List('h', 'u', 'f', 'f', 'm', 'a', 'n', 'e', 's', 't', 'c', 'o', 'o', 'l'))
  }

  test("encode") {
    encode(frenchCode)(List('h', 'u', 'f', 'f', 'm', 'a', 'n', 'e', 's', 't', 'c', 'o', 'o', 'l')) === List(0, 0, 1, 1, 1, 0, 1, 0, 1, 1, 1, 0, 0, 1, 1, 0, 1, 0, 0, 1, 1, 0, 1, 0, 1, 1, 0, 0, 1, 1, 1, 1, 1, 0, 1, 0, 1, 1, 0, 0, 0, 0, 1, 0, 1, 1, 1, 0, 0, 1, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 1)
  }

  test("convert") {
    val list = List('h', 'u', 'f', 'f', 'm', 'a', 'n', 'e', 's', 't', 'c', 'o', 'o', 'l')
    val codeTable = convert(frenchCode)
    assert(secret === (list flatMap (x => codeBits(codeTable)(x))))
  }

  test("quickencode") {
    val text = List('h', 'u', 'f', 'f', 'm', 'a', 'n', 'e', 's', 't', 'c', 'o', 'o', 'l')
    assert(secret === quickEncode(frenchCode)(text))
  }

  def visualizeTree(tree: CodeTree) = {
    def visualizeTree0(tree: CodeTree, totalRows: Int, totalCols: Int): Unit = {
      try {
        def constructTree(tree: CodeTree, row: Int, col: Int, result: Array[Array[String]]): Array[Array[String]] = {
          def generateString(tree: CodeTree): String = tree match {
            case Fork(left, right, chars, weight) => "(" + chars.mkString + ":" + weight + ")"
            case Leaf(char, weight) => "[" + char + ":" + weight + "]"
          }
          def addIfFork(tree: CodeTree, row: Int, col: Int, result: Array[Array[String]]): Array[Array[String]] = tree match {
            case Fork(left, right, chars, weight) =>
              result(row + 1)(col) = "*" //limiter under fork (will be replaced)
              //decrement or increment columns based on number of chars in fork
              constructTree(left, row + 3, col - (chars.length - 1), result)
              constructTree(right, row + 3, col + (chars.length - 1), result)
            case _ => result //do nothing
          }
          if (row > 0) {
            result(row - 2)(col) = "#" //limiter two characters above leaf (will be replaced)
            result(row - 1)(col) = "|" //connector
          }
          result(row)(col) = generateString(tree) //display leaf or fork
          addIfFork(tree, row, col, result)
        }
        val initialArray = Array.fill(totalRows, totalCols) { "" }
        val visualizableTree = constructTree(tree, 0, totalRows / 2, initialArray)
        printTree(visualizableTree)
      } catch {
        case e: ArrayIndexOutOfBoundsException =>
          visualizeTree0(tree, totalRows + 3, totalCols + 4) //tricrement rows, quadcrement cols and retry
      }
      def printTree(array: Array[Array[String]]): Unit = {
        def preserveWidth(field: String, colWidth: Int): String = {
          def preserveWidth(field: String, switchDirection: Boolean): String = {
            if (field.length > colWidth) field
            else if (switchDirection) preserveWidth(field + " ", !switchDirection)
            else preserveWidth(" " + field, !switchDirection)
          }
          preserveWidth(field, false)
        }
        def collapseCols(array: Array[Array[String]]): Array[Array[String]] = {
          def determineColWidth(array: Array[Array[String]]): Array[Int] = {
            val resultArray = Array.fill(totalCols) { 0 } //init columns to width 0
            for (r <- array.indices) {
              for (c <- array(r).indices) {
                if (array(r)(c).length > resultArray(c)) resultArray(c) = array(r)(c).length //increase column width if needed
              }
            }
            resultArray
          }
          val colWidths = determineColWidth(array)
          for (r <- array.indices) {
            for (c <- array(r).indices) {
              array(r)(c) = preserveWidth(array(r)(c), colWidths(c))
            }
          }
          array
        }
        def replaceLimiters(raw: String): String = {
          def replaceLimiters0(input: String, pattern: String, toRight: Boolean): String = {
            val builder = new StringBuilder(input)
            (pattern.r findAllIn input).matchData foreach { m =>
              for (s <- m.subgroups) {
                builder.delete(0, builder.length()).append(m.before)
                if (toRight) builder.append(" " + ("_" * (s.length - 3)) + "/*")
                else builder.append(" \\" + ("_" * (s.length - 3)) + " ")
                builder.append(m.after)
              }
            }
            (pattern.r findAllIn builder.toString).matchData foreach { m =>
              if (m.groupCount > 0) {
                val newLine = replaceLimiters0(builder.toString, pattern, toRight)
                builder.delete(0, builder.length()).append(newLine)
              }
            }
            builder.toString
          }
          replaceLimiters0(replaceLimiters0(raw, """([#][ ]*[*])""", true), """([*][ ]*[#])""", false)
        }
        collapseCols(array).foreach { line =>
          val rawOutput = line.mkString.replaceAll("""(?)\s+$""", "") //removes trailing spaces
          val printMe = replaceLimiters(rawOutput)
          if (printMe.length > 0) println(printMe)
        }
      }
    }
    visualizeTree0(tree, 1, 1)
  }
}

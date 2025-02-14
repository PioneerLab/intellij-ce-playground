/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.diff.comparison

import com.intellij.diff.DiffTestCase
import com.intellij.diff.fragments.DiffFragment
import com.intellij.diff.fragments.LineFragment
import com.intellij.diff.util.DiffUtil
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.util.Couple
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.util.text.StringUtil

public class ComparisonUtilAutoTest : DiffTestCase() {
  public fun testChar() {
    doTestChar(System.currentTimeMillis(), 30, 30)
  }

  public fun testWord() {
    doTestWord(System.currentTimeMillis(), 30, 300)
  }

  public fun testLine() {
    doTestLine(System.currentTimeMillis(), 30, 300)
  }

  public fun testLineSquashed() {
    doTestLineSquashed(System.currentTimeMillis(), 30, 300)
  }

  public fun testLineTrimSquashed() {
    doTestLineTrimSquashed(System.currentTimeMillis(), 30, 300)
  }

  private fun doTestLine(seed: Long, runs: Int, maxLength: Int) {
    val policies = listOf(ComparisonPolicy.DEFAULT, ComparisonPolicy.TRIM_WHITESPACES, ComparisonPolicy.IGNORE_WHITESPACES)

    doTest(seed, runs, maxLength, policies) { text1, text2, policy, debugData ->
      val sequence1 = text1.getCharsSequence()
      val sequence2 = text2.getCharsSequence()

      val fragments = MANAGER.compareLinesInner(sequence1, sequence2, policy, INDICATOR)
      debugData.put("Fragments", fragments)

      checkResultLine(text1, text2, fragments, policy, true)
    }
  }

  private fun doTestLineSquashed(seed: Long, runs: Int, maxLength: Int) {
    val policies = listOf(ComparisonPolicy.DEFAULT, ComparisonPolicy.TRIM_WHITESPACES, ComparisonPolicy.IGNORE_WHITESPACES)

    doTest(seed, runs, maxLength, policies) { text1, text2, policy, debugData ->
      val sequence1 = text1.getCharsSequence()
      val sequence2 = text2.getCharsSequence()

      val fragments = MANAGER.compareLinesInner(sequence1, sequence2, policy, INDICATOR)
      debugData.put("Fragments", fragments)

      val squashedFragments = MANAGER.squash(fragments)
      debugData.put("Squashed Fragments", squashedFragments)

      checkResultLine(text1, text2, squashedFragments, policy, false)
    }
  }

  private fun doTestLineTrimSquashed(seed: Long, runs: Int, maxLength: Int) {
    val policies = listOf(ComparisonPolicy.DEFAULT, ComparisonPolicy.TRIM_WHITESPACES, ComparisonPolicy.IGNORE_WHITESPACES)

    doTest(seed, runs, maxLength, policies) { text1, text2, policy, debugData ->
      val sequence1 = text1.getCharsSequence()
      val sequence2 = text2.getCharsSequence()

      val fragments = MANAGER.compareLinesInner(sequence1, sequence2, policy, INDICATOR)
      debugData.put("Fragments", fragments)

      val processed = MANAGER.processBlocks(fragments, sequence1, sequence2, policy, true, true)
      debugData.put("Processed Fragments", processed)

      checkResultLine(text1, text2, processed, policy, false)
    }
  }

  private fun doTestChar(seed: Long, runs: Int, maxLength: Int) {
    val policies = listOf(ComparisonPolicy.DEFAULT, ComparisonPolicy.IGNORE_WHITESPACES)

    doTest(seed, runs, maxLength, policies) { text1, text2, policy, debugData ->
      val sequence1 = text1.getCharsSequence()
      val sequence2 = text2.getCharsSequence()

      val fragments = MANAGER.compareChars(sequence1, sequence2, policy, INDICATOR)
      debugData.put("Fragments", fragments)

      checkResultChar(sequence1, sequence2, fragments, policy)
    }
  }

  private fun doTestWord(seed: Long, runs: Int, maxLength: Int) {
    val policies = listOf(ComparisonPolicy.DEFAULT, ComparisonPolicy.TRIM_WHITESPACES, ComparisonPolicy.IGNORE_WHITESPACES)

    doTest(seed, runs, maxLength, policies) { text1, text2, policy, debugData ->
      val sequence1 = text1.getCharsSequence()
      val sequence2 = text2.getCharsSequence()

      val fragments = MANAGER.compareWords(sequence1, sequence2, policy, INDICATOR)
      debugData.put("Fragments", fragments)

      checkResultWord(sequence1, sequence2, fragments, policy)
    }
  }

  private fun doTest(seed: Long, runs: Int, maxLength: Int, policies: List<ComparisonPolicy>,
                     test: (Document, Document, ComparisonPolicy, DiffTestCase.DebugData) -> Unit) {
    doAutoTest(seed, runs) { debugData ->
      debugData.put("MaxLength", maxLength)

      val text1 = DocumentImpl(generateText(maxLength))
      val text2 = DocumentImpl(generateText(maxLength))

      debugData.put("Text1", textToReadableFormat(text1.getCharsSequence()))
      debugData.put("Text2", textToReadableFormat(text2.getCharsSequence()))

      for (comparisonPolicy in policies) {
        debugData.put("Policy", comparisonPolicy)
        test(text1, text2, comparisonPolicy, debugData)
      }
    }
  }

  private fun checkResultLine(text1: Document, text2: Document, fragments: List<LineFragment>, policy: ComparisonPolicy, allowNonSquashed: Boolean) {
    checkLineConsistency(text1, text2, fragments, allowNonSquashed)

    for (fragment in fragments) {
      if (fragment.getInnerFragments() != null) {
        val sequence1 = text1.subsequence(fragment.getStartOffset1(), fragment.getEndOffset1())
        val sequence2 = text2.subsequence(fragment.getStartOffset2(), fragment.getEndOffset2())

        checkResultWord(sequence1, sequence2, fragment.getInnerFragments()!!, policy)
      }
    }

    checkUnchanged(text1.getCharsSequence(), text2.getCharsSequence(), fragments, policy, true)
    checkCantTrimLines(text1, text2, fragments, policy, allowNonSquashed)
  }

  private fun checkResultWord(text1: CharSequence, text2: CharSequence, fragments: List<DiffFragment>, policy: ComparisonPolicy) {
    checkDiffConsistency(fragments)

    checkUnchanged(text1, text2, fragments, policy, false)
  }

  private fun checkResultChar(text1: CharSequence, text2: CharSequence, fragments: List<DiffFragment>, policy: ComparisonPolicy) {
    checkDiffConsistency(fragments)

    checkUnchanged(text1, text2, fragments, policy, false)
  }

  private fun checkLineConsistency(text1: Document, text2: Document, fragments: List<LineFragment>, allowNonSquashed: Boolean) {
    var last1 = -1
    var last2 = -1

    for (fragment in fragments) {
      val startOffset1 = fragment.getStartOffset1()
      val startOffset2 = fragment.getStartOffset2()
      val endOffset1 = fragment.getEndOffset1()
      val endOffset2 = fragment.getEndOffset2()

      val start1 = fragment.getStartLine1()
      val start2 = fragment.getStartLine2()
      val end1 = fragment.getEndLine1()
      val end2 = fragment.getEndLine2()

      assertTrue(startOffset1 >= 0)
      assertTrue(startOffset2 >= 0)
      assertTrue(endOffset1 <= text1.getTextLength())
      assertTrue(endOffset2 <= text2.getTextLength())

      assertTrue(start1 >= 0)
      assertTrue(start2 >= 0)
      assertTrue(end1 <= getLineCount(text1))
      assertTrue(end2 <= getLineCount(text2))

      assertTrue(startOffset1 <= endOffset1)
      assertTrue(startOffset2 <= endOffset2)

      assertTrue(start1 <= end1)
      assertTrue(start2 <= end2)
      assertTrue(start1 != end1 || start2 != end2)

      assertTrue(allowNonSquashed || start1 != last1 || start2 != last2)

      checkLineOffsets(fragment, text1, text2)

      last1 = end1
      last2 = end2
    }
  }

  private fun checkDiffConsistency(fragments: List<DiffFragment>) {
    var last1 = -1
    var last2 = -1

    for (diffFragment in fragments) {
      val start1 = diffFragment.getStartOffset1()
      val start2 = diffFragment.getStartOffset2()
      val end1 = diffFragment.getEndOffset1()
      val end2 = diffFragment.getEndOffset2()

      assertTrue(start1 <= end1)
      assertTrue(start2 <= end2)
      assertTrue(start1 != end1 || start2 != end2)

      assertTrue(start1 != last1 || start2 != last2)

      last1 = end1
      last2 = end2
    }
  }

  private fun checkLineOffsets(fragment: LineFragment, before: Document, after: Document) {
    checkLineOffsets(before, fragment.getStartLine1(), fragment.getEndLine1(), fragment.getStartOffset1(), fragment.getEndOffset1())

    checkLineOffsets(after, fragment.getStartLine2(), fragment.getEndLine2(), fragment.getStartOffset2(), fragment.getEndOffset2())
  }

  private fun checkLineOffsets(document: Document, startLine: Int, endLine: Int, startOffset: Int, endOffset: Int) {
    if (startLine != endLine) {
      assertEquals(document.getLineStartOffset(startLine), startOffset)
      var offset = document.getLineEndOffset(endLine - 1)
      if (offset < document.getTextLength()) offset++
      assertEquals(offset, endOffset)
    }
    else {
      val offset = if (startLine == getLineCount(document))
        document.getTextLength()
      else
        document.getLineStartOffset(startLine)
      assertEquals(offset, startOffset)
      assertEquals(offset, endOffset)
    }
  }

  private fun checkUnchanged(text1: CharSequence, text2: CharSequence, fragments: List<DiffFragment>, policy: ComparisonPolicy, skipNewline: Boolean) {
    // TODO: better check for Trim spaces case ?
    val ignoreSpaces = policy !== ComparisonPolicy.DEFAULT

    var last1 = 0
    var last2 = 0
    for (fragment in fragments) {
      val chunk1 = text1.subSequence(last1, fragment.getStartOffset1())
      val chunk2 = text2.subSequence(last2, fragment.getStartOffset2())

      assertEqualsCharSequences(chunk1, chunk2, ignoreSpaces, skipNewline)

      last1 = fragment.getEndOffset1()
      last2 = fragment.getEndOffset2()
    }
    val chunk1 = text1.subSequence(last1, text1.length)
    val chunk2 = text2.subSequence(last2, text2.length)
    assertEqualsCharSequences(chunk1, chunk2, ignoreSpaces, skipNewline)
  }

  private fun checkCantTrimLines(text1: Document, text2: Document, fragments: List<LineFragment>, policy: ComparisonPolicy, allowNonSquashed: Boolean) {
    for (fragment in fragments) {
      val sequence1 = getFirstLastLines(text1, fragment.getStartLine1(), fragment.getEndLine1())
      val sequence2 = getFirstLastLines(text2, fragment.getStartLine2(), fragment.getEndLine2())
      if (sequence1 == null || sequence2 == null) continue

      checkNonEqualsIfLongEnough(sequence1.first, sequence2.first, policy, allowNonSquashed)
      checkNonEqualsIfLongEnough(sequence1.second, sequence2.second, policy, allowNonSquashed)
    }
  }

  private fun checkNonEqualsIfLongEnough(line1: CharSequence, line2: CharSequence, policy: ComparisonPolicy, allowNonSquashed: Boolean) {
    // in non-squashed blocks non-trimmed elements are possible, if it's 'unimportant' lines
    if (allowNonSquashed && countNonWhitespaceCharacters(line1) <= Registry.get("diff.unimportant.line.char.count").asInteger()) return
    if (allowNonSquashed && countNonWhitespaceCharacters(line2) <= Registry.get("diff.unimportant.line.char.count").asInteger()) return

    assertFalse(MANAGER.isEquals(line1, line2, policy))
  }

  private fun countNonWhitespaceCharacters(line: CharSequence): Int {
    var count = 0
    for (i in 0 until line.length) {
      if (!StringUtil.isWhiteSpace(line.get(i))) count++
    }
    return count
  }

  private fun getFirstLastLines(text: Document, start: Int, end: Int): Couple<CharSequence>? {
    if (start == end) return null

    val firstLineRange = DiffUtil.getLinesRange(text, start, start + 1)
    val lastLineRange = DiffUtil.getLinesRange(text, end - 1, end)

    val firstLine = firstLineRange.subSequence(text.getCharsSequence())
    val lastLine = lastLineRange.subSequence(text.getCharsSequence())

    return Couple.of(firstLine, lastLine)
  }

  private fun Document.subsequence(start: Int, end: Int): CharSequence {
    return this.getCharsSequence().subSequence(start, end)
  }
}

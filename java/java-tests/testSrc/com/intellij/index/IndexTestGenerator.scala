/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package com.intellij.index

import com.intellij.util.containers.ContainerUtil
import groovy.lang.GroovyClassLoader
import junit.framework.{TestResult, TestCase}
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen._
import org.scalacheck.Prop.{forAll, BooleanOperators}
import org.scalacheck._
import scala.collection.JavaConversions._

/**
 * Run this class to generate randomized tests for IDEA VFS/document/PSI/index subsystem interaction using ScalaCheck.<p/>
 *
 * When a test fails, a test method code (in Groovy) is printed which should be copied to a normal test class (IndexTest)
 * and debugged there.<p/>
 *
 * The generated test may contain some excessive declarations and checks that should be corrected manually.
 * After the fix, that generated test should be renamed according to the underlying issue it found and committed to the repository.
 *
 * @author peter
 */
object IndexTestGenerator {
  val genAction: Gen[Action] = oneOf(
    const(Gc),
    const(Commit),
    const(Save),
    const(PsiChange),
    const(InvisiblePsiChange),
    const(PostponedFormatting),
    const(Reformat),
    const(LoadViewProviderDocument),
    const(CheckStamps),
    for (withImport <- arbitrary[Boolean];
         viaDocument <- arbitrary[Boolean])
      yield TextChange(viaDocument, withImport),
    arbitrary[Boolean] map UpdatePsiClassRef,
    arbitrary[Boolean] map UpdatePsiFileRef,
    arbitrary[Boolean] map UpdateASTNodeRef,
    arbitrary[Boolean] map UpdateDocumentRef
  )
  val propIndexTest = forAll(Gen.nonEmptyListOf(genAction)) { actions =>
    containsChange(actions) ==> new IndexTestSeq(actions).isSuccessful
  }

  def containsChange(actions: List[Action]) = actions.exists {
    case TextChange(_, _) => true
    case PsiChange => true
    case _ => false
  }

  def main(args: Array[String]) {
    propIndexTest.check
    System.exit(0)
  }
}

case class IndexTestSeq(actions: List[Action]) {

  def printClass: String = {
    val sb = StringBuilder.newBuilder
    sb.append(prefix)
    sb.append(
    s"""
         |public void "$testName"() {
         |def psiFile = myFixture.addFileToProject("Foo.java", "class Foo {}")
         |def vFile = psiFile.virtualFile
         |def lastPsiName = "Foo"
         |long counterBefore
         |Document document
         |ASTNode astNode
         |PsiClass psiClass
         |def scope = GlobalSearchScope.allScope(project)
         |""".stripMargin)
    var changeId = 0
    var docClassName = "Foo"

    def printCommit = {
      sb.append(
        s"""PsiDocumentManager.getInstance(project).commitAllDocuments()
               |lastPsiName = "$docClassName"
               |""".stripMargin)
    }

    for (action <- actions) {
      sb.append("\n")
      action match {
        case Gc =>
          sb.append("PlatformTestUtil.tryGcSoftlyReachableObjects()\n")
        case LoadViewProviderDocument =>
          sb.append("assert psiManager.findFile(vFile).viewProvider.document\n")
        case PostponedFormatting =>
          sb.append(
            """PostprocessReformattingAspect.getInstance(getProject()).
              |  doPostponedFormatting()
              |""".stripMargin)
        case CheckStamps =>
          sb.append(
            """L:{
              |def pf = psiManager.findFile(vFile)
              |def vpStamp = pf.viewProvider.modificationStamp
              |def doc = FileDocumentManager.instance.getDocument(vFile)
              |def docStamp = doc.modificationStamp
              |def vfStamp = vFile.modificationStamp
              |//todo replace with assertions
              |if (FileDocumentManager.instance.unsavedDocuments) {
              |  assert docStamp > vfStamp
              |} else {
              |  assert docStamp == vfStamp
              |  assert doc.text == LoadTextUtil.loadText(vFile) as String
              |}
              |if (PsiDocumentManager.getInstance(project).uncommittedDocuments) {
              |  assert docStamp > vpStamp
              |} else {
              |  assert docStamp == vpStamp
              |  assert doc.text == pf.viewProvider.contents as String
              |  assert doc.text == pf.text
              |}
              |}
            """.stripMargin)
        case Reformat =>
          printCommit
          sb.append(
            s"""CodeStyleManager.getInstance(getProject()).
               |  reformat(psiManager.findFile(vFile))
               |""".stripMargin)
        case Commit =>
          printCommit
        case PsiChange =>
          printCommit
          sb.append(
            s"""((PsiJavaFile)psiManager.findFile(vFile)).importList.add(
               |  elementFactory.createImportStatementOnDemand("java.io"))
               |""".stripMargin)
        case InvisiblePsiChange =>
          printCommit
          sb.append(
             s"""L:{
                |  def cls = JavaPsiFacade.getInstance(project).findClass(lastPsiName, scope)
                |  cls.replace(cls.copy())
                |}
                |""".stripMargin)
        case Save =>
          sb.append("FileDocumentManager.instance.saveAllDocuments()\n")
        case UpdatePsiClassRef(load) =>
          sb.append("psiClass = " + (if (load) "JavaPsiFacade.getInstance(project).findClass(lastPsiName, scope)" else "null") + "\n")
          if (load) sb.append("assert psiClass\n")
        case UpdatePsiFileRef(load) =>
          sb.append("psiFile = " + (if (load) "psiManager.findFile(vFile)" else "null") + "\n")
        case UpdateASTNodeRef(load) =>
          sb.append("astNode = " + (if (load) "JavaPsiFacade.getInstance(project).findClass(lastPsiName, scope).node" else "null") + "\n")
        case UpdateDocumentRef(load) =>
          sb.append("document = " + (if (load) "FileDocumentManager.instance.getDocument(vFile)" else "null") + "\n")
        case TextChange(viaDocument, withImport) =>
          changeId += 1
          docClassName = "Foo" + changeId
          val newText = (if (withImport) "import zoo.Zoo; "  else "") + s"class $docClassName {\\n }"

          sb.append(
            """PostprocessReformattingAspect.getInstance(getProject()).
              |  doPostponedFormatting()
              |counterBefore =
              |  psiManager.modificationTracker.javaStructureModificationCount
              |""".stripMargin)

          if (viaDocument) {
            sb.append(
              s"""FileDocumentManager.instance.getDocument(vFile).text =
                 |  "$newText"
                 |""".stripMargin)
          } else {
            sb.append(
              s"""//todo remove if statement or replace with its content
                 |if (FileDocumentManager.instance.unsavedDocuments) {
                 |  FileDocumentManager.instance.saveAllDocuments()
                 |}
                 |VfsUtil.saveText(vFile, "$newText")
                 |""".stripMargin)
          }

          sb.append(
            s"""// todo replace if statement with assertions
               |if (!PsiDocumentManager.getInstance(project).uncommittedDocuments) {
               |  lastPsiName = "$docClassName"
               |  assert counterBefore !=
               |    psiManager.modificationTracker.javaStructureModificationCount
               |}
               |""".stripMargin)
      }
    }
    sb.append("}\n}")
    sb.toString()
  }

  val prefix =
    s"""import com.intellij.lang.ASTNode
       |import com.intellij.openapi.command.WriteCommandAction
       |import com.intellij.openapi.editor.Document
       |import com.intellij.openapi.fileEditor.FileDocumentManager
       |import com.intellij.openapi.util.Ref
       |import com.intellij.openapi.vfs.VfsUtil
       |import com.intellij.psi.*
       |import com.intellij.psi.codeStyle.*
       |import com.intellij.psi.impl.source.*
       |import com.intellij.psi.search.GlobalSearchScope
       |import com.intellij.testFramework.PlatformTestUtil
       |import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase
       |import com.intellij.util.ObjectUtils
       |import com.intellij.openapi.fileEditor.impl.LoadTextUtil
       |import org.jetbrains.annotations.NotNull
       |class DummyTest extends JavaCodeInsightFixtureTestCase {
       |protected void invokeTestRunnable(Runnable runnable) {
       |  WriteCommandAction.runWriteCommandAction(project, runnable)
       |}
       |""".stripMargin

  val testName: String = "test please write a meaningful description here"

  def isSuccessful: Boolean = {
    println(actions)

    val classText = printClass
    val test = new GroovyClassLoader().parseClass(classText).newInstance().asInstanceOf[TestCase]
    test.setName(testName)
    val result: TestResult = test.run()
    val successful: Boolean = result.wasSuccessful()

    if (!successful) {
      ContainerUtil.toList(result.failures()).foreach(failure => println(failure.trace()))
      ContainerUtil.toList(result.errors()).foreach(failure => println(failure.trace()))
      println(classText)
    }

    successful
  }

}

sealed trait Action
case object Gc extends Action
case object Commit extends Action
case object Save extends Action
case class TextChange(viaDocument: Boolean, withImport: Boolean) extends Action
case class UpdatePsiClassRef(load: Boolean) extends Action
case class UpdatePsiFileRef(load: Boolean) extends Action
case class UpdateDocumentRef(load: Boolean) extends Action
case class UpdateASTNodeRef(load: Boolean) extends Action
case object PsiChange extends Action
case object InvisiblePsiChange extends Action
case object PostponedFormatting extends Action
case object Reformat extends Action
case object LoadViewProviderDocument extends Action
case object CheckStamps extends Action

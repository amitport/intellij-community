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
package com.jetbrains.python.console;

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.execution.console.LanguageConsole;
import com.intellij.execution.console.LanguageConsoleView;
import com.intellij.execution.console.ProcessBackedConsoleExecuteActionHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.application.Result;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorModificationUtil;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileTypes.PlainTextLanguage;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.codeStyle.CodeStyleSettingsManager;
import com.intellij.psi.impl.source.codeStyle.IndentHelperImpl;
import com.intellij.util.Function;
import com.intellij.util.ui.UIUtil;
import com.jetbrains.python.PythonFileType;
import com.jetbrains.python.PythonLanguage;
import com.jetbrains.python.console.pydev.ConsoleCommunication;
import com.jetbrains.python.console.pydev.ConsoleCommunicationListener;
import com.jetbrains.python.console.pydev.InterpreterResponse;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;

/**
 * @author traff
 */
public class PydevConsoleExecuteActionHandler extends ProcessBackedConsoleExecuteActionHandler implements ConsoleCommunicationListener {
  private final LanguageConsoleView myConsoleView;

  private String myInMultilineStringState = null;
  private StringBuilder myInputBuffer;
  private int myCurrentIndentSize = 0;

  private final ConsoleCommunication myConsoleCommunication;
  private boolean myEnabled = false;

  private int myIpythonInputPromptCount = 1;

  public PydevConsoleExecuteActionHandler(LanguageConsoleView consoleView,
                                          ProcessHandler processHandler,
                                          ConsoleCommunication consoleCommunication) {
    super(processHandler, false);
    myConsoleView = consoleView;
    myConsoleCommunication = consoleCommunication;
    myConsoleCommunication.addCommunicationListener(this);
  }

  @Override
  public void processLine(@NotNull final String text) {
    processLine(text, false);
  }

  public void processLine(@NotNull final String text, boolean execAnyway) {
    int indentBefore = myCurrentIndentSize;
    if (text.isEmpty()) {
      processOneLine(text);
    }
    else {
      if (StringUtil.countNewLines(text.trim()) > 0) {
        executeMultiLine(text);
      }
      else {
        processOneLine(text);
      }
    }
    if (execAnyway && myCurrentIndentSize > 0 && indentBefore == 0) { //if code was indented and we need to exec anyway
      finishExecution();
    }
  }

  private void executeMultiLine(@NotNull String text) {
    if (myInputBuffer == null) {
      myInputBuffer = new StringBuilder();
    }

    myInputBuffer.append(text);

    final LanguageConsole console = myConsoleView.getConsole();
    final Editor currentEditor = console.getConsoleEditor();

    sendLineToConsole(new ConsoleCommunication.ConsoleCodeFragment(myInputBuffer.toString(), false), console, currentEditor);
  }

  private void processOneLine(String line) {
    int indentSize = IndentHelperImpl.getIndent(getProject(), PythonFileType.INSTANCE, line, false);
    line = StringUtil.trimTrailing(line);
    if (StringUtil.isEmptyOrSpaces(line)) {
      doProcessLine("\n");
    }
    else if (indentSize == 0 &&
             indentSize < myCurrentIndentSize &&
             !PyConsoleIndentUtil.shouldIndent(line) &&
             !myConsoleCommunication.isWaitingForInput()) {
      doProcessLine("\n");
      doProcessLine(line);
    }
    else {
      doProcessLine(line);
    }
  }

  public void doProcessLine(final String line) {
    final LanguageConsole console = myConsoleView.getConsole();
    final Editor currentEditor = console.getConsoleEditor();

    if (myInputBuffer == null) {
      myInputBuffer = new StringBuilder();
    }

    if (!StringUtil.isEmptyOrSpaces(line)) {
      myInputBuffer.append(line);
      if (!line.endsWith("\n")) {
        myInputBuffer.append("\n");
      }
    }

    if (StringUtil.isEmptyOrSpaces(line) && StringUtil.isEmptyOrSpaces(myInputBuffer.toString())) {
      myInputBuffer.append("");
    }

    // multiline strings handling
    if (myInMultilineStringState != null) {
      if (PyConsoleUtil.isDoubleQuoteMultilineStarts(line) || PyConsoleUtil.isSingleQuoteMultilineStarts(line)) {
        myInMultilineStringState = null;
        // restore language
        console.setLanguage(PythonLanguage.getInstance());
        console.setPrompt(PyConsoleUtil.ORDINARY_PROMPT);
      } else {
        if(line.equals("\n")) {
          myInputBuffer.append("\n");
        }
        return;
      }
    }
    else {
      if (PyConsoleUtil.isDoubleQuoteMultilineStarts(line)) {
        myInMultilineStringState = PyConsoleUtil.DOUBLE_QUOTE_MULTILINE;
      }
      else if (PyConsoleUtil.isSingleQuoteMultilineStarts(line)) {
        myInMultilineStringState = PyConsoleUtil.SINGLE_QUOTE_MULTILINE;
      }
      if (myInMultilineStringState != null) {
        // change language
        console.setLanguage(PlainTextLanguage.INSTANCE);
        console.setPrompt(PyConsoleUtil.INDENT_PROMPT);
        return;
      }
    }

    // Process line continuation
    if (line.endsWith("\\")) {
      console.setPrompt(PyConsoleUtil.INDENT_PROMPT);
      return;
    }

    if (!StringUtil.isEmptyOrSpaces(line)) {
      int indent = IndentHelperImpl.getIndent(getProject(), PythonFileType.INSTANCE, line, false);
      boolean flag = false;
      if (PyConsoleIndentUtil.shouldIndent(line)) {
        indent += getPythonIndent();
        flag = true;
      }
      if ((myCurrentIndentSize > 0 && indent > 0) || flag) {
        setCurrentIndentSize(indent);
        indentEditor(currentEditor, indent);
        more(console, currentEditor);

        myConsoleCommunication.notifyCommandExecuted(true);
        return;
      }
    }


    sendLineToConsole(new ConsoleCommunication.ConsoleCodeFragment(myInputBuffer.toString(), true), console, currentEditor);
  }

  private void sendLineToConsole(@NotNull final ConsoleCommunication.ConsoleCodeFragment code,
                                 @NotNull final LanguageConsole console,
                                 @NotNull final Editor currentEditor) {
    if(!StringUtil.isEmptyOrSpaces(code.getText())) {
      myIpythonInputPromptCount+=1;
    }
    if (myConsoleCommunication != null) {
      final boolean waitedForInputBefore = myConsoleCommunication.isWaitingForInput();
      if (myConsoleCommunication.isWaitingForInput()) {
        myInputBuffer.setLength(0);
      }
      else {
        executingPrompt(console);
      }
      myConsoleCommunication.execInterpreter(code, new Function<InterpreterResponse, Object>() {
        @Override
        public Object fun(final InterpreterResponse interpreterResponse) {
          // clear
          myInputBuffer = null;
          // Handle prompt
          if (interpreterResponse.more) {
            more(console, currentEditor);
            if (myCurrentIndentSize == 0) {
              // compute current indentation
              setCurrentIndentSize(
                IndentHelperImpl.getIndent(getProject(), PythonFileType.INSTANCE, lastLine(code.getText()), false) + getPythonIndent());
              // In this case we can insert indent automatically
              UIUtil.invokeLaterIfNeeded(new Runnable() {
                @Override
                public void run() {
                  indentEditor(currentEditor, myCurrentIndentSize);
                }
              });
            }
          }
          else {
            if (!myConsoleCommunication.isWaitingForInput()) {
              inPrompt(console, currentEditor);
            }
            setCurrentIndentSize(0);
          }

          return null;
        }
      });
      // After requesting input we got no call back to change prompt, change it manually
      if (waitedForInputBefore && !myConsoleCommunication.isWaitingForInput()) {
        myIpythonInputPromptCount-=1;
        inPrompt(console, currentEditor);
        setCurrentIndentSize(0);
      }
    }
  }

  private static String lastLine(@NotNull String text) {
    String[] lines = StringUtil.splitByLinesDontTrim(text);
    return lines[lines.length - 1];
  }

  private void inPrompt(LanguageConsole console, Editor currentEditor){
    if(ipythonEnabled(console)){
      ipythonInPrompt(console, currentEditor);
    } else {
      ordinaryPrompt(console, currentEditor);
    }
  }

  private void ordinaryPrompt(LanguageConsole console, Editor currentEditor) {
    if (!myConsoleCommunication.isExecuting()) {
      if (!PyConsoleUtil.ORDINARY_PROMPT.equals(console.getPrompt())) {
        console.setPrompt(PyConsoleUtil.ORDINARY_PROMPT);
        PyConsoleUtil.scrollDown(currentEditor);
      }
    }
    else {
      executingPrompt(console);
    }
  }

  private boolean ipythonEnabled(LanguageConsole console){
    return console.getFile().getVirtualFile() != null ?
           PyConsoleUtil.getOrCreateIPythonData(console.getFile().getVirtualFile()).isIPythonEnabled() : false;
  }

  private void ipythonInPrompt(LanguageConsole console, Editor currentEditor){
    TextAttributes attributes = ConsoleViewContentType.USER_INPUT.getAttributes();
    attributes.setFontType(Font.PLAIN);
    console.setPromptAttributes(attributes);
    console.setPrompt("In[" + myIpythonInputPromptCount + "]:");
    PyConsoleUtil.scrollDown(currentEditor);
  }

  private static void executingPrompt(LanguageConsole console) {
    console.setPrompt(PyConsoleUtil.EXECUTING_PROMPT);
  }

  private static void more(LanguageConsole console, Editor currentEditor) {
    if (!PyConsoleUtil.INDENT_PROMPT.equals(console.getPrompt())) {
      console.setPrompt(PyConsoleUtil.INDENT_PROMPT);
      PyConsoleUtil.scrollDown(currentEditor);
    }
  }

  public static String getPrevCommandRunningMessage() {
    return "Previous command is still running. Please wait or press Ctrl+C in console to interrupt.";
  }

  @Override
  public void commandExecuted(boolean more) {
    if (!more) {
      final LanguageConsole console = myConsoleView.getConsole();
      final Editor currentEditor = console.getConsoleEditor();

      if(!ipythonEnabled(console)){
        ordinaryPrompt(console, currentEditor);
      }
    }
  }

  @Override
  public void inputRequested() {
    final LanguageConsole console = myConsoleView.getConsole();
    final Editor currentEditor = console.getConsoleEditor();

    if (!PyConsoleUtil.INPUT_PROMPT.equals(console.getPrompt()) && !PyConsoleUtil.HELP_PROMPT.equals(console.getPrompt())) {
      console.setPrompt(PyConsoleUtil.INPUT_PROMPT);
      PyConsoleUtil.scrollDown(currentEditor);
    }
    setCurrentIndentSize(1);
  }

  public void finishExecution() {
    final LanguageConsole console = myConsoleView.getConsole();
    final Editor currentEditor = console.getConsoleEditor();

    if (myInputBuffer != null) {
      processLine("\n");
    }

    cleanEditor(currentEditor);
    //console.setPrompt(PyConsoleHighlightingUtil.ORDINARY_PROMPT);
  }

  public int getCurrentIndentSize() {
    return myCurrentIndentSize;
  }

  public void setCurrentIndentSize(int currentIndentSize) {
    myCurrentIndentSize = currentIndentSize;
    VirtualFile file = getConsoleFile();
    if (file != null) {
      PyConsoleUtil.setCurrentIndentSize(file, currentIndentSize);
    }
  }

  @Nullable
  private VirtualFile getConsoleFile() {
    if (myConsoleView != null) {
      return myConsoleView.getConsole().getFile().getVirtualFile();
    }
    else {
      return null;
    }
  }

  public int getPythonIndent() {
    return CodeStyleSettingsManager.getSettings(getProject()).getIndentSize(PythonFileType.INSTANCE);
  }

  private void indentEditor(final Editor editor, final int indentSize) {
    new WriteCommandAction(getProject()) {
      @Override
      protected void run(@NotNull Result result) throws Throwable {
        EditorModificationUtil.insertStringAtCaret(editor, IndentHelperImpl.fillIndent(getProject(), PythonFileType.INSTANCE, indentSize));
      }
    }.execute();
  }

  private void cleanEditor(final Editor editor) {
    new WriteCommandAction(getProject()) {
      @Override
      protected void run(@NotNull Result result) throws Throwable {
        editor.getDocument().setText("");
      }
    }.execute();
  }

  private Project getProject() {
    return myConsoleView.getConsole().getProject();
  }

  public String getCantExecuteMessage() {
    if (!isEnabled()) {
      return getConsoleIsNotEnabledMessage();
    }
    else if (!canExecuteNow()) {
      return getPrevCommandRunningMessage();
    }
    else {
      return "Can't execute the command";
    }
  }

  @Override
  public void runExecuteAction(@NotNull LanguageConsoleView console) {
    if (isEnabled()) {
      if (!canExecuteNow()) {
        HintManager.getInstance().showErrorHint(console.getConsole().getConsoleEditor(), getPrevCommandRunningMessage());
      }
      else {
        doRunExecuteAction(console);
      }
    }
    else {
      HintManager.getInstance().showErrorHint(console.getConsole().getConsoleEditor(), getConsoleIsNotEnabledMessage());
    }
  }

  private void doRunExecuteAction(LanguageConsoleView console) {
    if (shouldCopyToHistory(console.getConsole())) {
      copyToHistoryAndExecute(console);
    }
    else {
      processLine(console.getConsole().getConsoleEditor().getDocument().getText());
    }
  }

  private static boolean shouldCopyToHistory(@NotNull LanguageConsole console) {
    return !PyConsoleUtil.isPagingPrompt(console.getPrompt());
  }

  private void copyToHistoryAndExecute(LanguageConsoleView console) {
    super.runExecuteAction(console);
  }

  public boolean canExecuteNow() {
    return !myConsoleCommunication.isExecuting() || myConsoleCommunication.isWaitingForInput();
  }

  protected String getConsoleIsNotEnabledMessage() {
    return "Console is not enabled.";
  }

  protected void setEnabled(boolean flag) {
    myEnabled = flag;
  }

  public boolean isEnabled() {
    return myEnabled;
  }
}

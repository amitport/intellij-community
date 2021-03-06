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
package com.intellij.codeInsight.daemon.impl;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.UserDataHolderEx;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiMatcherImpl;
import com.intellij.psi.util.PsiMatchers;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.ArrayUtilRt;
import com.intellij.util.containers.BidirectionalMap;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class RefCountHolder {
  private static final Logger LOG = Logger.getInstance("#com.intellij.codeInsight.daemon.impl.RefCountHolder");

  private final PsiFile myFile;
  private final BidirectionalMap<PsiReference,PsiElement> myLocalRefsMap = new BidirectionalMap<PsiReference, PsiElement>();

  private final Map<PsiAnchor, Boolean> myDclsUsedMap = ContainerUtil.newConcurrentMap();
  private final Map<PsiReference, PsiImportStatementBase> myImportStatements = ContainerUtil.newConcurrentMap();
  private final AtomicReference<ProgressIndicator> myState = new AtomicReference<ProgressIndicator>(READY);
  private static final ProgressIndicator READY = new DaemonProgressIndicator() {
    {
      cancel();
    }
    @Override
    public String toString() {
      return "READY";
    }
  };

  private static final Key<Reference<RefCountHolder>> REF_COUNT_HOLDER_IN_FILE_KEY = Key.create("REF_COUNT_HOLDER_IN_FILE_KEY");

  @NotNull
  public static RefCountHolder get(@NotNull PsiFile file) {
    Reference<RefCountHolder> ref = file.getUserData(REF_COUNT_HOLDER_IN_FILE_KEY);
    RefCountHolder holder = com.intellij.reference.SoftReference.dereference(ref);
    if (holder == null) {
      holder = new RefCountHolder(file);
      Reference<RefCountHolder> newRef = new SoftReference<RefCountHolder>(holder);
      while (true) {
        boolean replaced = ((UserDataHolderEx)file).replace(REF_COUNT_HOLDER_IN_FILE_KEY, ref, newRef);
        if (replaced) {
          break;
        }
        ref = file.getUserData(REF_COUNT_HOLDER_IN_FILE_KEY);
        RefCountHolder newHolder = com.intellij.reference.SoftReference.dereference(ref);
        if (newHolder != null) {
          holder = newHolder;
          break;
        }
      }
    }
    return holder;
  }

  private RefCountHolder(@NotNull PsiFile file) {
    myFile = file;
    log("c: created for ", file);
  }

  private void clear() {
    synchronized (myLocalRefsMap) {
      myLocalRefsMap.clear();
    }
    myImportStatements.clear();
    myDclsUsedMap.clear();
  }

  public void registerLocallyReferenced(@NotNull PsiNamedElement result) {
    myDclsUsedMap.put(PsiAnchor.create(result), Boolean.TRUE);
  }

  public void registerReference(@NotNull PsiJavaReference ref, @NotNull JavaResolveResult resolveResult) {
    PsiElement refElement = resolveResult.getElement();
    PsiFile psiFile = refElement == null ? null : refElement.getContainingFile();
    if (psiFile != null) psiFile = (PsiFile)psiFile.getNavigationElement(); // look at navigation elements because all references resolve into Cls elements when highlighting library source
    if (refElement != null && psiFile != null && myFile.getViewProvider().equals(psiFile.getViewProvider())) {
      registerLocalRef(ref, refElement.getNavigationElement());
    }

    PsiElement resolveScope = resolveResult.getCurrentFileResolveScope();
    if (resolveScope instanceof PsiImportStatementBase) {
      registerImportStatement(ref, (PsiImportStatementBase)resolveScope);
    }
  }

  private void registerImportStatement(@NotNull PsiReference ref, @NotNull PsiImportStatementBase importStatement) {
    myImportStatements.put(ref, importStatement);
  }

  boolean isRedundant(@NotNull PsiImportStatementBase importStatement) {
    return !myImportStatements.containsValue(importStatement);
  }

  private void registerLocalRef(@NotNull PsiReference ref, PsiElement refElement) {
    if (refElement instanceof PsiMethod && PsiTreeUtil.isAncestor(refElement, ref.getElement(), true)) return; // filter self-recursive calls
    if (refElement instanceof PsiClass && PsiTreeUtil.isAncestor(refElement, ref.getElement(), true)) return; // filter inner use of itself
    synchronized (myLocalRefsMap) {
      myLocalRefsMap.put(ref, refElement);
    }
  }

  private void removeInvalidRefs() {
    synchronized (myLocalRefsMap) {
      for(Iterator<PsiReference> iterator = myLocalRefsMap.keySet().iterator(); iterator.hasNext();){
        PsiReference ref = iterator.next();
        if (!ref.getElement().isValid()){
          PsiElement value = myLocalRefsMap.get(ref);
          iterator.remove();
          List<PsiReference> array = myLocalRefsMap.getKeysByValue(value);
          LOG.assertTrue(array != null);
          array.remove(ref);
        }
      }
    }
    for (Iterator<PsiReference> iterator = myImportStatements.keySet().iterator(); iterator.hasNext();) {
      PsiReference ref = iterator.next();
      if (!ref.getElement().isValid()) {
        iterator.remove();
      }
    }
    removeInvalidFrom(myDclsUsedMap.keySet());
  }

  private static void removeInvalidFrom(@NotNull Collection<? extends PsiAnchor> collection) {
    for (Iterator<? extends PsiAnchor> it = collection.iterator(); it.hasNext();) {
      PsiAnchor element = it.next();
      if (element.retrieve() == null) it.remove();
    }
  }

  public boolean isReferenced(@NotNull PsiNamedElement element) {
    List<PsiReference> array;
    synchronized (myLocalRefsMap) {
      array = myLocalRefsMap.getKeysByValue(element);
    }
    if (array != null && !array.isEmpty() && !isParameterUsedRecursively(element, array)) return true;

    Boolean usedStatus = myDclsUsedMap.get(PsiAnchor.create(element));
    return usedStatus == Boolean.TRUE;
  }

  boolean isReferencedByMethodReference(@NotNull PsiMethod method, @NotNull LanguageLevel languageLevel) {
    if (!languageLevel.isAtLeast(LanguageLevel.JDK_1_8)) return false;

    List<PsiReference> array;
    synchronized (myLocalRefsMap) {
      array = myLocalRefsMap.getKeysByValue(method);
    }

    if (array != null && !array.isEmpty()) {
      for (PsiReference reference : array) {
        final PsiElement element = reference.getElement();
        if (element != null && element instanceof PsiMethodReferenceExpression) {
          return true;
        }
      }
    }

    return false;
  }

  private static boolean isParameterUsedRecursively(@NotNull PsiElement element, @NotNull List<PsiReference> array) {
    if (!(element instanceof PsiParameter)) return false;
    PsiParameter parameter = (PsiParameter)element;
    PsiElement scope = parameter.getDeclarationScope();
    if (!(scope instanceof PsiMethod)) return false;
    PsiMethod method = (PsiMethod)scope;
    int paramIndex = ArrayUtilRt.find(method.getParameterList().getParameters(), parameter);

    for (PsiReference reference : array) {
      if (!(reference instanceof PsiElement)) return false;
      PsiElement argument = (PsiElement)reference;

      PsiMethodCallExpression methodCallExpression = (PsiMethodCallExpression)new PsiMatcherImpl(argument)
        .dot(PsiMatchers.hasClass(PsiReferenceExpression.class))
        .parent(PsiMatchers.hasClass(PsiExpressionList.class))
        .parent(PsiMatchers.hasClass(PsiMethodCallExpression.class))
        .getElement();
      if (methodCallExpression == null) return false;
      PsiReferenceExpression methodExpression = methodCallExpression.getMethodExpression();
      if (method != methodExpression.resolve()) return false;
      PsiExpressionList argumentList = methodCallExpression.getArgumentList();
      PsiExpression[] arguments = argumentList.getExpressions();
      int argumentIndex = ArrayUtilRt.find(arguments, argument);
      if (paramIndex != argumentIndex) return false;
    }

    return true;
  }

  boolean isReferencedForRead(@NotNull PsiVariable variable) {
    List<PsiReference> array;
    synchronized (myLocalRefsMap) {
      array = myLocalRefsMap.getKeysByValue(variable);
    }
    if (array == null) return false;
    for (PsiReference ref : array) {
      PsiElement refElement = ref.getElement();
      if (!(refElement instanceof PsiExpression)) { // possible with incomplete code
        return true;
      }
      if (PsiUtil.isAccessedForReading((PsiExpression)refElement)) {
        if (refElement.getParent() instanceof PsiExpression &&
            refElement.getParent().getParent() instanceof PsiExpressionStatement &&
            PsiUtil.isAccessedForWriting((PsiExpression)refElement)) {
          continue; // "var++;"
        }
        return true;
      }
    }
    return false;
  }

  boolean isReferencedForWrite(@NotNull PsiVariable variable) {
    List<PsiReference> array;
    synchronized (myLocalRefsMap) {
      array = myLocalRefsMap.getKeysByValue(variable);
    }
    if (array == null) return false;
    for (PsiReference ref : array) {
      final PsiElement refElement = ref.getElement();
      if (!(refElement instanceof PsiExpression)) { // possible with incomplete code
        return true;
      }
      if (PsiUtil.isAccessedForWriting((PsiExpression)refElement)) {
        return true;
      }
    }
    return false;
  }

  public boolean analyze(@NotNull PsiFile file,
                         TextRange dirtyScope,
                         @NotNull ProgressIndicator indicator,
                         @NotNull Runnable analyze) {
    if (!myState.compareAndSet(READY, indicator)) {
      log("a: failed to change ", myState, "->", indicator);
      return false;
    }
    log("a: changed ", myState, "->", indicator);
    try {
      if (dirtyScope != null) {
        if (dirtyScope.equals(file.getTextRange())) {
          clear();
        }
        else {
          removeInvalidRefs();
        }
      }

      analyze.run();
      return true;
    }
    finally {
      boolean set = myState.compareAndSet(indicator, READY);
      assert set : myState.get();
      log("a: changed after analyze", indicator, "->", READY);
    }
  }

  private static void log(@NonNls @NotNull Object... info) {
    FileStatusMap.log(info);
  }
}

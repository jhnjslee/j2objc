/*
 * Copyright 2011 Google Inc. All Rights Reserved.
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

package com.google.devtools.j2objc.gen;

import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.devtools.j2objc.J2ObjC;
import com.google.devtools.j2objc.Options;
import com.google.devtools.j2objc.ast.AbstractTypeDeclaration;
import com.google.devtools.j2objc.ast.Annotation;
import com.google.devtools.j2objc.ast.AnnotationTypeDeclaration;
import com.google.devtools.j2objc.ast.AnnotationTypeMemberDeclaration;
import com.google.devtools.j2objc.ast.BodyDeclaration;
import com.google.devtools.j2objc.ast.CompilationUnit;
import com.google.devtools.j2objc.ast.EnumConstantDeclaration;
import com.google.devtools.j2objc.ast.EnumDeclaration;
import com.google.devtools.j2objc.ast.Expression;
import com.google.devtools.j2objc.ast.FieldDeclaration;
import com.google.devtools.j2objc.ast.FunctionDeclaration;
import com.google.devtools.j2objc.ast.MethodDeclaration;
import com.google.devtools.j2objc.ast.NativeDeclaration;
import com.google.devtools.j2objc.ast.PackageDeclaration;
import com.google.devtools.j2objc.ast.Statement;
import com.google.devtools.j2objc.ast.TreeUtil;
import com.google.devtools.j2objc.ast.TypeDeclaration;
import com.google.devtools.j2objc.ast.VariableDeclarationFragment;
import com.google.devtools.j2objc.types.ImplementationImportCollector;
import com.google.devtools.j2objc.types.Import;
import com.google.devtools.j2objc.util.BindingUtil;
import com.google.devtools.j2objc.util.NameTable;
import com.google.devtools.j2objc.util.TranslationUtil;

import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.Modifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Generates Objective-C implementation (.m) files from compilation units.
 *
 * @author Tom Ball
 */
public class ObjectiveCImplementationGenerator extends ObjectiveCSourceFileGenerator {

  private final String suffix;

  /**
   * Generate an Objective-C implementation file for each type declared in a
   * specified compilation unit.
   */
  public static void generate(GenerationUnit unit) {
    new ObjectiveCImplementationGenerator(unit).generate();
  }

  private ObjectiveCImplementationGenerator(GenerationUnit unit) {
    super(unit, Options.emitLineDirectives());
    suffix = Options.getImplementationFileSuffix();
  }

  @Override
  protected String getSuffix() {
    return suffix;
  }

  private void setGenerationContext(AbstractTypeDeclaration type) {
    TreeUtil.getCompilationUnit(type).setGenerationContext();
  }

  public void generate() {
    List<CompilationUnit> units = getGenerationUnit().getCompilationUnits();
    List<AbstractTypeDeclaration> types = collectTypes(units);
    List<CompilationUnit> packageInfos = collectPackageInfos(units);

    println(J2ObjC.getFileHeader(getGenerationUnit().getSourceName()));
    if (!types.isEmpty() || !packageInfos.isEmpty()) {
      printStart(getGenerationUnit().getSourceName());
      printImports();
      for (CompilationUnit packageInfo: packageInfos) {
        packageInfo.setGenerationContext();
        generatePackageInfo(packageInfo);
      }

      if (!types.isEmpty()) {
        printIgnoreIncompletePragmas(units);
        pushIgnoreDeprecatedDeclarationsPragma();
        printPrivateDeclarations(types);
        printClassExtensions(types);
        for (AbstractTypeDeclaration type : types) {
          setGenerationContext(type);
          generateTypeImplementation(type);
        }
        popIgnoreDeprecatedDeclarationsPragma();
      }
    }

    save(getOutputPath());
  }

  private List<AbstractTypeDeclaration> collectTypes(List<CompilationUnit> units) {
    final List<AbstractTypeDeclaration> types = new ArrayList<AbstractTypeDeclaration>();

    for (CompilationUnit unit : units) {
      for (AbstractTypeDeclaration type : unit.getTypes()) {
        types.add(type);
      }
    }

    return types;
  }

  private List<CompilationUnit> collectPackageInfos(List<CompilationUnit> units) {
    List<CompilationUnit> packageInfos = new ArrayList<CompilationUnit>();

    for (CompilationUnit unit : units) {
      unit.setGenerationContext();
      if (unit.getMainTypeName().endsWith(NameTable.PACKAGE_INFO_MAIN_TYPE)) {
        PackageDeclaration pkg = unit.getPackage();
        if (TreeUtil.getRuntimeAnnotationsList(pkg.getAnnotations()).size() > 0 &&
            TranslationUtil.needsReflection(pkg)) {
          packageInfos.add(unit);
        }
      }
    }

    return packageInfos;
  }

  private void generateTypeImplementation(AbstractTypeDeclaration node) {
    printInitFlagDefinition(node);
    printStaticVars(node);
    generateSpecificTypeImplementation(node);
    printOuterDefinitions(node);
    printTypeLiteralImplementation(node);
  }

  @SuppressWarnings("incomplete-switch")
  private void generateSpecificTypeImplementation(AbstractTypeDeclaration node) {
    switch (node.getKind()) {
      case ANNOTATION_TYPE_DECLARATION:
        generateAnnotationTypeImplementation((AnnotationTypeDeclaration) node);
        break;
      case ENUM_DECLARATION:
        generateEnumTypeImplementation((EnumDeclaration) node);
        break;
      case TYPE_DECLARATION:
        if (((TypeDeclaration) node).isInterface()) {
          generateInterfaceTypeImplementation((TypeDeclaration) node);
        } else {
          generateClassTypeImplementation((TypeDeclaration) node);
        }
    }
  }

  private void generateClassTypeImplementation(TypeDeclaration node) {
    String typeName = NameTable.getFullName(node.getTypeBinding());
    newline();
    syncLineNumbers(node.getName()); // avoid doc-comment
    printf("@implementation %s\n", typeName);
    printInnerDefinitions(node);
    printInitializeMethod(node);
    if (TranslationUtil.needsReflection(node)) {
      RuntimeAnnotationGenerator annotationGen = new RuntimeAnnotationGenerator(getBuilder());
      annotationGen.printTypeAnnotationsMethod(node);
      annotationGen.printMethodAnnotationMethods(TreeUtil.getMethodDeclarations(node));
      annotationGen.printFieldAnnotationMethods(node);
      printMetadata(node);
    }
    println("\n@end");
  }

  private void generateInterfaceTypeImplementation(TypeDeclaration node) {
    String typeName = NameTable.getFullName(node.getTypeBinding());
    boolean needsReflection = TranslationUtil.needsReflection(node);
    boolean needsImplementation = hasInitializeMethod(node) || needsReflection;
    if (needsImplementation && !hasInitializeMethod(node)) {
      printf("\n@interface %s : NSObject\n@end\n", typeName);
    }
    if (!needsImplementation) {
      return;
    }
    printf("\n@implementation %s\n", typeName);
    printInitializeMethod(node);
    if (needsReflection) {
      printMetadata(node);
    }
    println("\n@end");
  }

  private void generateEnumTypeImplementation(EnumDeclaration node) {
    List<EnumConstantDeclaration> constants = node.getEnumConstants();

    String typeName = NameTable.getFullName(node.getTypeBinding());
    newline();
    printf("%s *%s_values_[%s];\n", typeName, typeName, constants.size());

    newline();
    syncLineNumbers(node.getName()); // avoid doc-comment
    printf("@implementation %s\n", typeName);

    printInnerDefinitions(node);
    printInitializeMethod(node);

    if (TranslationUtil.needsReflection(node)) {
      new RuntimeAnnotationGenerator(getBuilder()).printTypeAnnotationsMethod(node);
      printMetadata(node);
    }
    println("\n@end");
  }

  private void generateAnnotationTypeImplementation(AnnotationTypeDeclaration node) {
    boolean isRuntime = BindingUtil.isRuntimeAnnotation(node.getTypeBinding());
    boolean hasInitMethod = hasInitializeMethod(node);
    boolean needsReflection = TranslationUtil.needsReflection(node);
    String typeName = NameTable.getFullName(node.getTypeBinding());

    if (needsReflection && !isRuntime && !hasInitMethod) {
      printf("\n@interface %s : NSObject\n@end\n", typeName);
    }

    if (isRuntime || hasInitMethod || needsReflection) {
      syncLineNumbers(node.getName()); // avoid doc-comment
      printf("\n@implementation %s\n", typeName);

      if (isRuntime) {
        List<AnnotationTypeMemberDeclaration> members = TreeUtil.getAnnotationMembers(node);
        printAnnotationProperties(members);
        if (!members.isEmpty()) {
          printAnnotationConstructor(node.getTypeBinding());
        }
        printAnnotationAccessors(members);
        println("\n- (IOSClass *)annotationType {");
        printf("  return %s_class_();\n", typeName);
        println("}");
        println("\n- (NSString *)description {");
        printf("  return @\"@%s()\";\n", node.getTypeBinding().getBinaryName());
        println("}");
      }
      printInitializeMethod(node);
      if (needsReflection) {
        new RuntimeAnnotationGenerator(getBuilder()).printTypeAnnotationsMethod(node);
        printMetadata(node);
      }
      println("\n@end");
    }
  }

  private void printIgnoreIncompletePragmas(List<CompilationUnit> units) {
    boolean needsNewline = true;

    for (CompilationUnit unit : units) {
      if (unit.hasIncompleteProtocol()) {
        newline();
        needsNewline = false;
        println("#pragma clang diagnostic ignored \"-Wprotocol\"");
        break;
      }
    }

    for (CompilationUnit unit : units) {
      if (unit.hasIncompleteImplementation()) {
        if (needsNewline) {
          newline();
        }
        println("#pragma clang diagnostic ignored \"-Wincomplete-implementation\"");
        break;
      }
    }
  }

  private void printAnnotationConstructor(ITypeBinding annotation) {
    newline();
    print(annotationConstructorDeclaration(annotation));
    println(" {");
    println("  if ((self = [super init])) {");
    for (IMethodBinding member : annotation.getDeclaredMethods()) {
      String name = NameTable.getAnnotationPropertyVariableName(member);
      printf("    self->%s = ", name);
      ITypeBinding type = member.getReturnType();
      boolean needsRetain = !type.isPrimitive();
      if (needsRetain) {
        print("RETAIN_(");
      }
      printf("%s__", NameTable.getAnnotationPropertyName(member));
      if (needsRetain) {
        print(')');
      }
      println(";");
    }
    println("  }");
    println("  return self;");
    println("}");
  }

  private void printAnnotationAccessors(List<AnnotationTypeMemberDeclaration> members) {
    for (AnnotationTypeMemberDeclaration member : members) {
      Expression deflt = member.getDefault();
      if (deflt != null) {
        ITypeBinding type = member.getType().getTypeBinding();
        String typeString = NameTable.getSpecificObjCType(type);
        String propertyName = NameTable.getAnnotationPropertyName(member.getMethodBinding());
        printf("\n+ (%s)%sDefault {\n", typeString, propertyName);
        printf("  return %s;\n", generateExpression(deflt));
        println("}");
      }
    }
  }

  private void generatePackageInfo(CompilationUnit unit) {
    PackageDeclaration node = unit.getPackage();
    List<Annotation> runtimeAnnotations = TreeUtil.getRuntimeAnnotationsList(node.getAnnotations());
    newline();
    String typeName = NameTable.camelCaseQualifiedName(node.getPackageBinding().getName())
        + NameTable.PACKAGE_INFO_MAIN_TYPE;
    printf("@interface %s : NSObject\n", typeName);
    printf("@end\n\n");
    printf("@implementation %s\n", typeName);
    new RuntimeAnnotationGenerator(getBuilder()).printPackageAnnotationMethod(node);
    println("\n@end");
  }

  private void printNativeDefinition(NativeDeclaration declaration) {
    newline();
    String code = declaration.getImplementationCode();
    if (code != null) {
      println(reindent(code));
    }
  }

  private void printInitFlagDefinition(AbstractTypeDeclaration node) {
    ITypeBinding binding = node.getTypeBinding();
    String typeName = NameTable.getFullName(binding);
    if (hasInitializeMethod(node)) {
      printf("\nJ2OBJC_INITIALIZED_DEFN(%s)\n", typeName);
    }
  }

  private void printInnerDefinitions(AbstractTypeDeclaration node) {
    printDefinitions(getInnerDefinitions(node));
  }

  private void printOuterDefinitions(AbstractTypeDeclaration node) {
    printDefinitions(getOuterDefinitions(node));
  }

  private void printDefinitions(Iterable<BodyDeclaration> declarations) {
    for (BodyDeclaration declaration : declarations) {
      printDefinition(declaration);
    }
  }

  private void printDefinition(BodyDeclaration declaration) {
    switch (declaration.getKind()) {
      case FUNCTION_DECLARATION:
        printFunctionDefinition((FunctionDeclaration) declaration);
        return;
      case METHOD_DECLARATION:
        printMethodDefinition((MethodDeclaration) declaration);
        return;
      case NATIVE_DECLARATION:
        printNativeDefinition((NativeDeclaration) declaration);
        return;
      default:
        break;
    }
  }

  private void printMethodDefinition(MethodDeclaration m) {
    if (Modifier.isAbstract(m.getModifiers())) {
      return;
    }
    newline();
    syncLineNumbers(m.getName());  // avoid doc-comment
    String methodBody = generateStatement(m.getBody(), /* isFunction */ false);
    print(super.methodDeclaration(m) + " " + reindent(methodBody) + "\n");
  }

  private void printInitializeMethod(AbstractTypeDeclaration typeNode) {
    List<Statement> initStatements = typeNode.getClassInitStatements();
    if (initStatements.isEmpty()) {
      return;
    }
    String className = NameTable.getFullName(typeNode.getTypeBinding());
    StringBuffer sb = new StringBuffer();
    sb.append("{\nif (self == [" + className + " class]) {\n");
    for (Statement statement : initStatements) {
      sb.append(generateStatement(statement, false));
    }
    sb.append("J2OBJC_SET_INITIALIZED(" + className + ")\n");
    sb.append("}\n}");
    print("\n+ (void)initialize " + reindent(sb.toString()) + "\n");
  }

  private String generateStatement(Statement stmt, boolean asFunction) {
    return StatementGenerator.generate(stmt, asFunction, getBuilder().getCurrentLine());
  }

  private String generateExpression(Expression expr) {
    return StatementGenerator.generate(expr, false, getBuilder().getCurrentLine());
  }

  private void printImports() {
    ImplementationImportCollector collector = new ImplementationImportCollector();
    collector.collect(getGenerationUnit().getCompilationUnits());
    Set<Import> imports = collector.getImports();

    Set<String> includeStmts = Sets.newTreeSet();
    includeStmts.add("#include \"J2ObjC_source.h\"");
    for (Import imp : imports) {
      includeStmts.add(String.format("#include \"%s.h\"", imp.getImportFileName()));
    }

    newline();
    for (String stmt : includeStmts) {
      println(stmt);
    }

    for (CompilationUnit node: getGenerationUnit().getCompilationUnits()) {
      for (NativeDeclaration decl : node.getNativeBlocks()) {
        printNativeDefinition(decl);
      }
    }
  }

  @Override
  protected void printStaticFieldDeclaration(
      VariableDeclarationFragment fragment, String baseDeclaration) {
    Expression initializer = fragment.getInitializer();
    print("static " + baseDeclaration);
    if (initializer != null) {
      print(" = " + generateExpression(initializer));
    }
    println(";");
  }

  private void printStaticVars(AbstractTypeDeclaration node) {
    boolean needsNewline = true;
    for (FieldDeclaration field : getStaticFields(node)) {
      if (shouldPrintDeclaration(field)) {
        // Static var is defined in declaration.
        continue;
      }
      for (VariableDeclarationFragment var : field.getFragments()) {
        IVariableBinding binding = var.getVariableBinding();
        Expression initializer = var.getInitializer();
        if (BindingUtil.isPrimitiveConstant(binding)) {
          continue;
        } else if (needsNewline) {
          needsNewline = false;
          newline();
        }
        String name = NameTable.getStaticVarQualifiedName(binding);
        String objcType = NameTable.getObjCType(binding.getType());
        objcType += objcType.endsWith("*") ? "" : " ";
        if (initializer != null) {
          printf("%s%s = %s;\n", objcType, name, generateExpression(initializer));
        } else {
          printf("%s%s;\n", objcType, name);
        }
      }
    }
  }

  private void printTypeLiteralImplementation(AbstractTypeDeclaration node) {
    ITypeBinding binding = node.getTypeBinding();
    newline();
    printf("J2OBJC_%s_TYPE_LITERAL_SOURCE(%s)\n",
        binding.isInterface() ? "INTERFACE" : "CLASS", NameTable.getFullName(binding));
  }

  private void printPrivateDeclarations(List<AbstractTypeDeclaration> types) {
    for (AbstractTypeDeclaration type : types) {
      setGenerationContext(type);
      printConstantDefines(type);
      printStaticFieldDeclarations(type);
      printOuterDeclarations(type);
    }
  }

  @Override
  protected void printFunctionDeclaration(FunctionDeclaration function) {
    newline();
    // We expect native functions to be defined externally.
    if (!Modifier.isNative(function.getModifiers())) {
      print("__attribute__((unused)) static ");
    }
    print(getFunctionSignature(function));
    if (function.returnsRetained()) {
      print(" NS_RETURNS_RETAINED");
    }
    println(";");
  }

  private void printFunctionDefinition(FunctionDeclaration function) {
    if (Modifier.isNative(function.getModifiers())) {
      return;
    }
    String functionBody = generateStatement(function.getBody(), /* isFunction */ true);
    newline();
    println(getFunctionSignature(function) + " " + reindent(functionBody));
  }

  private void printAnnotationProperties(List<AnnotationTypeMemberDeclaration> members) {
    if (!members.isEmpty()) {
      newline();
    }
    for (AnnotationTypeMemberDeclaration member : members) {
      IMethodBinding memberBinding = member.getMethodBinding();
      println(String.format("@synthesize %s = %s;",
          NameTable.getAnnotationPropertyName(memberBinding),
          NameTable.getAnnotationPropertyVariableName(memberBinding)));
    }
  }

  private void printMetadata(AbstractTypeDeclaration node) {
    print(new MetadataGenerator(node).getMetadataSource());
  }

  private void printClassExtensions(List<AbstractTypeDeclaration> types) {
    for (AbstractTypeDeclaration type : types) {
      setGenerationContext(type);
      if (type.getTypeBinding().isClass() || type.getTypeBinding().isEnum()) {
        printClassExtension(type);
      }
    }
  }

  private void printClassExtension(AbstractTypeDeclaration node) {
    if (Options.hidePrivateMembers()) {
      Iterable<FieldDeclaration> privateFields = getFieldsToDeclare(node);
      boolean hasPrivateFields = !Iterables.isEmpty(privateFields);
      Iterable<BodyDeclaration> privateDecls = getInnerDeclarations(node);
      if (!Iterables.isEmpty(privateDecls) || hasPrivateFields) {
        String typeName = NameTable.getFullName(node.getTypeBinding());
        newline();
        printf("@interface %s ()", typeName);
        if (hasPrivateFields) {
          println(" {");
          printInstanceVariables(privateFields);
          println("}");
        } else {
          newline();
        }
        printDeclarations(privateDecls);
        println("@end");
        printFieldSetters(node);
      }
    }
  }

  @Override
  protected boolean printPrivateDeclarations() {
    return true;
  }
}

package com.squareup.anvil.compiler.codegen

import com.squareup.anvil.annotations.ContributesTo
import com.squareup.anvil.compiler.ANVIL_MODULE_SUFFIX
import com.squareup.anvil.compiler.AnvilCompilationException
import com.squareup.anvil.compiler.ClassScanner
import com.squareup.anvil.compiler.HINT_BINDING_PACKAGE_PREFIX
import com.squareup.anvil.compiler.HINT_CONTRIBUTES_PACKAGE_PREFIX
import com.squareup.anvil.compiler.HINT_MULTIBINDING_PACKAGE_PREFIX
import com.squareup.anvil.compiler.MODULE_PACKAGE_PREFIX
import com.squareup.anvil.compiler.annotation
import com.squareup.anvil.compiler.annotationOrNull
import com.squareup.anvil.compiler.argumentType
import com.squareup.anvil.compiler.boundType
import com.squareup.anvil.compiler.classDescriptorForType
import com.squareup.anvil.compiler.codegen.CodeGenerator.GeneratedFile
import com.squareup.anvil.compiler.codegen.GeneratedMethod.BindingMethod
import com.squareup.anvil.compiler.codegen.GeneratedMethod.ProviderMethod
import com.squareup.anvil.compiler.contributesBindingFqName
import com.squareup.anvil.compiler.contributesMultibindingFqName
import com.squareup.anvil.compiler.contributesToFqName
import com.squareup.anvil.compiler.daggerModuleFqName
import com.squareup.anvil.compiler.generateClassName
import com.squareup.anvil.compiler.getAnnotationValue
import com.squareup.anvil.compiler.isQualifier
import com.squareup.anvil.compiler.mergeComponentFqName
import com.squareup.anvil.compiler.mergeModulesFqName
import com.squareup.anvil.compiler.mergeSubcomponentFqName
import com.squareup.anvil.compiler.replaces
import com.squareup.anvil.compiler.scope
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier.ABSTRACT
import com.squareup.kotlinpoet.TypeSpec
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoSet
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.resolveClassByFqName
import org.jetbrains.kotlin.incremental.KotlinLookupLocation
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtClassLiteralExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.constants.ArrayValue
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.descriptorUtil.getAllSuperClassifiers
import org.jetbrains.kotlin.types.KotlinType
import java.io.File
import java.util.Locale.US

private val supportedFqNames = listOf(
  mergeComponentFqName,
  mergeSubcomponentFqName,
  mergeModulesFqName
)

internal class BindingModuleGenerator(
  private val classScanner: ClassScanner
) : CodeGenerator {

  // Keeps track of for which scopes which files were generated. Usually there is only one file,
  // but technically there can be multiple.
  private val mergedScopes = mutableMapOf<FqName, MutableList<Pair<File, KtClassOrObject>>>()
    .withDefault { mutableListOf() }

  private val excludedTypesForScope = mutableMapOf<FqName, List<ClassDescriptor>>()

  private val contributedBindingClasses = mutableListOf<FqName>()
  private val contributedMultibindingClasses = mutableListOf<FqName>()
  private val contributedModuleAndInterfaceClasses = mutableListOf<FqName>()

  override fun generateCode(
    codeGenDir: File,
    module: ModuleDescriptor,
    projectFiles: Collection<KtFile>
  ): Collection<GeneratedFile> {
    // Even though we support multiple rounds the Kotlin compiler let's us generate code only once.
    // It's possible that we use the @ContributesBinding annotation in the module in which we
    // merge components. Remember for which classes a hint was generated and generate a @Binds
    // method for them later.
    contributedBindingClasses += findContributedBindingClasses(
      module = module,
      projectFiles = projectFiles,
      hintPackagePrefix = HINT_BINDING_PACKAGE_PREFIX
    )
    contributedMultibindingClasses += findContributedBindingClasses(
      module = module,
      projectFiles = projectFiles,
      hintPackagePrefix = HINT_MULTIBINDING_PACKAGE_PREFIX
    )

    val classes = projectFiles.flatMap {
      it.classesAndInnerClasses()
        .toList()
    }

    // Similar to the explanation above, we must track contributed modules.
    findContributedModules(classes)

    // Generate a Dagger module for each @MergeComponent and friends.
    return classes
      .filter { psiClass -> supportedFqNames.any { psiClass.hasAnnotation(it) } }
      .map { psiClass ->
        val classDescriptor =
          module.resolveClassByFqName(psiClass.requireFqName(), KotlinLookupLocation(psiClass))
            ?: throw AnvilCompilationException(
              "Couldn't resolve class for PSI element.",
              element = psiClass
            )

        // The annotation must be present due to the filter above.
        val mergeAnnotation = supportedFqNames
          .mapNotNull { classDescriptor.annotationOrNull(it) }
          .first()

        val scope = mergeAnnotation.scope(module).fqNameSafe

        // Remember for which scopes which types were excluded so that we later don't generate
        // a binding method for these types.
        (mergeAnnotation.getAnnotationValue("exclude") as? ArrayValue)?.value
          ?.map {
            it.argumentType(module).classDescriptorForType()
          }
          ?.let { excludedTypesForScope[scope] = it }

        val packageName = generatePackageName(psiClass)
        val className = psiClass.generateClassName()

        val directory = File(codeGenDir, packageName.replace('.', File.separatorChar))
        val file = File(directory, "$className.kt")
        check(file.parentFile.exists() || file.parentFile.mkdirs()) {
          "Could not generate package directory: ${file.parentFile}"
        }

        // We cheat and don't actually write the content to the file. We write the content in the
        // flush() method when we collected all binding methods to avoid two writes.
        check(file.exists() || file.createNewFile()) {
          "Could not create new file: $file"
        }

        val content = daggerModuleContent(
          scope = scope.asString(),
          psiClass = psiClass,
          generatedMethods = emptyList()
        )

        mergedScopes[scope] = mergedScopes.getValue(scope)
          .apply { this += file to psiClass }

        GeneratedFile(file, content)
      }
      .toList()
  }

  override fun flush(
    codeGenDir: File,
    module: ModuleDescriptor
  ): Collection<GeneratedFile> {
    return mergedScopes.flatMap { (scope, daggerModuleFiles) ->
      // Contributed Dagger modules can replace other Dagger modules but also contributed bindings.
      // If a binding is replaced, then we must not generate the binding method.
      //
      // We precompute this list here and share the result in the methods below. Resolving classes
      // and types can be an expensive operation, so avoid doing it twice.
      val bindingsReplacedInDaggerModules = contributedModuleAndInterfaceClasses
        .asSequence()
        .mapNotNull { module.resolveClassByFqName(it, NoLookupLocation.FROM_BACKEND) }
        .plus(
          classScanner.findContributedClasses(
            module,
            packageName = HINT_CONTRIBUTES_PACKAGE_PREFIX,
            annotation = contributesToFqName,
            scope = scope
          )
        )
        .filter { it.annotationOrNull(daggerModuleFqName) != null }
        .flatMap {
          it.annotationOrNull(contributesToFqName, scope)
            ?.replaces(module)
            ?.asSequence()
            ?: emptySequence()
        }
        .toList()

      // Note that this is an inner function to share some of the parameters. It computes all
      // generated functions for contributed bindings and multibindings. The generated functions
      // are so similar that it makes sense to share the code between normal binding and
      // multibinding methods.
      fun getContributedBindingClasses(
        collectedClasses: List<FqName>,
        hintPackagePrefix: String,
        annotationFqName: FqName,
        isMultibinding: Boolean
      ): List<GeneratedMethod> {
        val contributedBindingsThisModule = collectedClasses
          .asSequence()
          .mapNotNull { clazz ->
            module.resolveClassByFqName(clazz, NoLookupLocation.FROM_BACKEND)
          }
        val contributedBindingsDependencies = classScanner.findContributedClasses(
          module,
          packageName = hintPackagePrefix,
          annotation = annotationFqName,
          scope = scope
        )

        val replacedBindings = (contributedBindingsThisModule + contributedBindingsDependencies)
          .flatMap {
            it.annotationOrNull(annotationFqName, scope = scope)
              ?.replaces(module)
              ?.asSequence()
              ?: emptySequence()
          }

        return (contributedBindingsThisModule + contributedBindingsDependencies)
          .minus(replacedBindings)
          .minus(bindingsReplacedInDaggerModules)
          .minus(excludedTypesForScope[scope].orEmpty())
          .filter {
            val annotation = it.annotationOrNull(annotationFqName)
            annotation != null && scope == annotation.scope(module).fqNameSafe
          }
          .map { contributedClass ->
            val annotation = contributedClass.annotation(annotationFqName)
            val boundType = annotation.boundType(module, contributedClass, isMultibinding)

            checkExtendsBoundType(type = contributedClass, boundType = boundType)
            checkNotGeneric(type = contributedClass, boundTypeDescriptor = boundType)

            val concreteType = contributedClass.fqNameSafe

            val qualifiers = contributedClass.annotations
              .filter { it.isQualifier() }
              .map { it.toAnnotationSpec(module) }

            if (DescriptorUtils.isObject(contributedClass)) {
              ProviderMethod(
                FunSpec
                  .builder(
                    name = concreteType
                      .asString()
                      .split(".")
                      .joinToString(
                        separator = "",
                        prefix = "provide",
                        postfix = if (isMultibinding) "Multi" else ""
                      ) {
                        it.capitalize(US)
                      }
                  )
                  .addAnnotation(Provides::class)
                  .apply {
                    if (isMultibinding) {
                      addAnnotation(IntoSet::class)
                    }
                  }
                  .addAnnotations(qualifiers)
                  .returns(boundType.asClassName())
                  .addStatement("return %T", contributedClass.asClassName())
                  .build()
              )
            } else {
              BindingMethod(
                FunSpec
                  .builder(
                    name = concreteType
                      .asString()
                      .split(".")
                      .joinToString(
                        separator = "",
                        prefix = "bind",
                        postfix = if (isMultibinding) "Multi" else ""
                      ) {
                        it.capitalize(US)
                      }
                  )
                  .addAnnotation(Binds::class)
                  .apply {
                    if (isMultibinding) {
                      addAnnotation(IntoSet::class)
                    }
                  }
                  .addAnnotations(qualifiers)
                  .addModifiers(ABSTRACT)
                  .addParameter(
                    name = concreteType.shortName().asString().decapitalize(US),
                    type = contributedClass.asClassName()
                  )
                  .returns(boundType.asClassName())
                  .build()
              )
            }
          }
          .toList()
      }

      val generatedMethods = getContributedBindingClasses(
        collectedClasses = contributedBindingClasses,
        hintPackagePrefix = HINT_BINDING_PACKAGE_PREFIX,
        annotationFqName = contributesBindingFqName,
        isMultibinding = false
      ) + getContributedBindingClasses(
        collectedClasses = contributedMultibindingClasses,
        hintPackagePrefix = HINT_MULTIBINDING_PACKAGE_PREFIX,
        annotationFqName = contributesMultibindingFqName,
        isMultibinding = true
      )

      daggerModuleFiles.map { (file, psiClass) ->
        val content = daggerModuleContent(
          scope = scope.asString(),
          psiClass = psiClass,
          generatedMethods = generatedMethods
        )

        file.writeText(content)

        GeneratedFile(file, content)
      }
    }
  }

  private fun checkNotGeneric(
    type: ClassDescriptor,
    boundTypeDescriptor: ClassDescriptor
  ) {
    if (boundTypeDescriptor.declaredTypeParameters.isNotEmpty()) {

      fun KotlinType.describeTypeParameters(): String = arguments
        .ifEmpty { return "" }
        .joinToString(prefix = "<", postfix = ">") { typeArgument ->
          typeArgument.type.classDescriptorForType().name.asString() +
            typeArgument.type.describeTypeParameters()
        }

      val boundType = type.typeConstructor
        .supertypes
        .first { it.classDescriptorForType() == boundTypeDescriptor }

      throw AnvilCompilationException(
        classDescriptor = boundTypeDescriptor,
        message = "Binding ${boundTypeDescriptor.fqNameSafe} contains type parameters(s)" +
          " ${boundType.describeTypeParameters()}." +
          " Type parameters in bindings are not supported. This binding needs" +
          " to be contributed to a dagger module manually"
      )
    }
  }

  private fun findContributedBindingClasses(
    module: ModuleDescriptor,
    projectFiles: Collection<KtFile>,
    hintPackagePrefix: String
  ): List<FqName> {
    return projectFiles
      .filter {
        it.packageFqName.asString().startsWith(hintPackagePrefix)
      }
      .flatMap {
        it.findChildrenByClass(KtProperty::class.java).toList()
      }
      .mapNotNull { ktProperty ->
        (ktProperty.initializer as? KtClassLiteralExpression)
          ?.firstChild
          ?.requireFqName(module)
      }
  }

  private fun findContributedModules(classes: List<KtClassOrObject>) {
    contributedModuleAndInterfaceClasses += classes
      .filter { it.hasAnnotation(contributesToFqName) }
      .map { it.requireFqName() }
  }

  private fun checkExtendsBoundType(
    type: ClassDescriptor,
    boundType: ClassDescriptor
  ) {
    val boundFqName = boundType.fqNameSafe
    val hasSuperType = type.getAllSuperClassifiers()
      .any { it.fqNameSafe == boundFqName }

    if (!hasSuperType) {
      throw AnvilCompilationException(
        classDescriptor = type,
        message = "${type.fqNameSafe} contributes a binding for $boundFqName, but doesn't " +
          "extend this type."
      )
    }
  }

  private fun KtClassOrObject.generateClassName(): String =
    generateClassName(separator = "") + ANVIL_MODULE_SUFFIX

  private fun generatePackageName(psiClass: KtClassOrObject): String =
    "$MODULE_PACKAGE_PREFIX.${psiClass.containingKtFile.packageFqName}"

  private fun daggerModuleContent(
    scope: String,
    psiClass: KtClassOrObject,
    generatedMethods: List<GeneratedMethod>
  ): String {
    val className = psiClass.generateClassName()

    val bindingMethods = generatedMethods.filterIsInstance<BindingMethod>()
    val providerMethods = generatedMethods.filterIsInstance<ProviderMethod>()

    return FileSpec.buildFile(generatePackageName(psiClass), className) {
      val builder = if (bindingMethods.isEmpty()) {
        TypeSpec.objectBuilder(className)
          .addFunctions(providerMethods.specs)
      } else {
        TypeSpec.classBuilder(className)
          .addModifiers(ABSTRACT)
          .addFunctions(bindingMethods.specs)
          .apply {
            if (providerMethods.isNotEmpty()) {
              addType(
                TypeSpec.companionObjectBuilder()
                  .addFunctions(providerMethods.specs)
                  .build()
              )
            }
          }
      }

      addType(
        builder
          .addAnnotation(Module::class)
          .addAnnotation(
            AnnotationSpec
              .builder(ContributesTo::class)
              .addMember("$scope::class")
              .build()
          )
          .build()
      )
    }
  }
}

private sealed class GeneratedMethod {
  abstract val spec: FunSpec

  class ProviderMethod(override val spec: FunSpec) : GeneratedMethod()
  class BindingMethod(override val spec: FunSpec) : GeneratedMethod()
}

private val Collection<GeneratedMethod>.specs get() = map { it.spec }

/*
 * Copyright 2010-2016 JetBrains s.r.o.
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

package org.jetbrains.kotlin.cli.jvm.compiler

import com.intellij.openapi.util.io.JarUtil
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.asJava.FilteredJvmDiagnostics
import org.jetbrains.kotlin.backend.common.output.OutputFileCollection
import org.jetbrains.kotlin.backend.common.output.SimpleOutputFileCollection
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.messages.*
import org.jetbrains.kotlin.cli.common.output.outputUtils.writeAll
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.cli.jvm.config.addJavaSourceRoot
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoot
import org.jetbrains.kotlin.cli.jvm.config.jvmClasspathRoots
import org.jetbrains.kotlin.codegen.ClassBuilderFactories
import org.jetbrains.kotlin.codegen.CompilationErrorHandler
import org.jetbrains.kotlin.codegen.GeneratedClassLoader
import org.jetbrains.kotlin.codegen.KotlinCodegenFacade
import org.jetbrains.kotlin.codegen.state.GenerationState
import org.jetbrains.kotlin.codegen.state.GenerationStateEventCallback
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.config.addKotlinSourceRoots
import org.jetbrains.kotlin.fileClasses.JvmFileClassUtil
import org.jetbrains.kotlin.idea.MainFunctionDetector
import org.jetbrains.kotlin.load.kotlin.ModuleVisibilityManager
import org.jetbrains.kotlin.modules.Module
import org.jetbrains.kotlin.modules.TargetId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.isSubpackageOf
import org.jetbrains.kotlin.progress.ProgressIndicatorAndCompilationCanceledStatus
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.jvm.TopDownAnalyzerFacadeForJVM
import org.jetbrains.kotlin.util.PerformanceCounter
import org.jetbrains.kotlin.utils.KotlinPaths
import org.jetbrains.kotlin.utils.PathUtil
import org.jetbrains.kotlin.utils.newLinkedHashMapWithExpectedSize
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes

object KotlinToJVMBytecodeCompiler {

    private fun getAbsolutePaths(directory: File, module: Module): List<String> {
        return module.getSourceFiles().map { sourceFile ->
            var source = File(sourceFile)
            if (!source.isAbsolute) {
                source = File(directory, sourceFile)
            }
            source.absolutePath
        }
    }

    private fun writeOutput(
            configuration: CompilerConfiguration,
            outputFiles: OutputFileCollection,
            mainClass: FqName?
    ) {
        val jarPath = configuration.get(JVMConfigurationKeys.OUTPUT_JAR)
        if (jarPath != null) {
            val includeRuntime = configuration.get(JVMConfigurationKeys.INCLUDE_RUNTIME, false)
            CompileEnvironmentUtil.writeToJar(jarPath, includeRuntime, mainClass, outputFiles)
            return
        }

        val outputDir = configuration.get(JVMConfigurationKeys.OUTPUT_DIRECTORY) ?: File(".")
        val messageCollector = configuration.get(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
        outputFiles.writeAll(outputDir, messageCollector)
    }

    private fun createOutputFilesFlushingCallbackIfPossible(configuration: CompilerConfiguration): GenerationStateEventCallback {
        if (configuration.get(JVMConfigurationKeys.OUTPUT_DIRECTORY) == null) {
            return GenerationStateEventCallback.DO_NOTHING
        }
        return GenerationStateEventCallback { state ->
            val currentOutput = SimpleOutputFileCollection(state.factory.currentOutput)
            writeOutput(configuration, currentOutput, mainClass = null)
            state.factory.releaseGeneratedOutput()
        }
    }

    fun compileModules(environment: KotlinCoreEnvironment, directory: File): Boolean {
        ProgressIndicatorAndCompilationCanceledStatus.checkCanceled()

        val moduleVisibilityManager = ModuleVisibilityManager.SERVICE.getInstance(environment.project)

        val projectConfiguration = environment.configuration
        val chunk = projectConfiguration.getNotNull(JVMConfigurationKeys.MODULES)
        for (module in chunk) {
            moduleVisibilityManager.addModule(module)
        }

        val friendPaths = environment.configuration.getList(JVMConfigurationKeys.FRIEND_PATHS)
        for (path in friendPaths) {
            moduleVisibilityManager.addFriendPath(path)
        }

        val targetDescription = "in targets [" + chunk.joinToString { input -> input.getModuleName() + "-" + input.getModuleType() } + "]"
        val result = analyze(environment, targetDescription)
        if (result == null || !result.shouldGenerateCode) return false

        ProgressIndicatorAndCompilationCanceledStatus.checkCanceled()

        result.throwIfError()

        val outputs = newLinkedHashMapWithExpectedSize<Module, GenerationState>(chunk.size)

        for (module in chunk) {
            ProgressIndicatorAndCompilationCanceledStatus.checkCanceled()
            val ktFiles = CompileEnvironmentUtil.getKtFiles(
                    environment.project, getAbsolutePaths(directory, module), projectConfiguration
            ) { path -> throw IllegalStateException("Should have been checked before: $path") }
            if (!checkKotlinPackageUsage(environment, ktFiles)) return false

            val moduleConfiguration = projectConfiguration.copy().apply {
                put(JVMConfigurationKeys.OUTPUT_DIRECTORY, File(module.getOutputDirectory()))
            }

            outputs[module] = generate(environment, moduleConfiguration, result, ktFiles, module)
        }

        try {
            for ((module, state) in outputs) {
                ProgressIndicatorAndCompilationCanceledStatus.checkCanceled()
                writeOutput(state.configuration, state.factory, null)
            }
            return true
        }
        finally {
            outputs.values.forEach(GenerationState::destroy)
        }
    }

    fun configureSourceRoots(configuration: CompilerConfiguration, chunk: List<Module>, directory: File) {
        for (module in chunk) {
            configuration.addKotlinSourceRoots(getAbsolutePaths(directory, module))
        }

        for (module in chunk) {
            for (javaRootPath in module.getJavaSourceRoots()) {
                configuration.addJavaSourceRoot(File(javaRootPath.path), javaRootPath.packagePrefix)
            }
        }

        for (module in chunk) {
            for (classpathRoot in module.getClasspathRoots()) {
                configuration.addJvmClasspathRoot(File(classpathRoot))
            }
        }

        configuration.addAll(JVMConfigurationKeys.MODULES, chunk)
    }

    private fun findMainClass(generationState: GenerationState, files: List<KtFile>): FqName? {
        val mainFunctionDetector = MainFunctionDetector(generationState.bindingContext)
        return files.asSequence()
                .map { file ->
                    if (mainFunctionDetector.hasMain(file.declarations))
                        JvmFileClassUtil.getFileClassInfoNoResolve(file).facadeClassFqName
                    else
                        null
                }
                .singleOrNull { it != null }
    }

    fun compileBunchOfSources(environment: KotlinCoreEnvironment): Boolean {
        val moduleVisibilityManager = ModuleVisibilityManager.SERVICE.getInstance(environment.project)

        val friendPaths = environment.configuration.getList(JVMConfigurationKeys.FRIEND_PATHS)
        for (path in friendPaths) {
            moduleVisibilityManager.addFriendPath(path)
        }

        if (!checkKotlinPackageUsage(environment, environment.getSourceFiles())) return false

        val generationState = analyzeAndGenerate(environment) ?: return false

        val mainClass = findMainClass(generationState, environment.getSourceFiles())

        try {
            writeOutput(environment.configuration, generationState.factory, mainClass)
            return true
        }
        finally {
            generationState.destroy()
        }
    }

    fun compileAndExecuteScript(
            configuration: CompilerConfiguration,
            environment: KotlinCoreEnvironment,
            paths: KotlinPaths,
            scriptArgs: List<String>): ExitCode
    {
        val scriptClass = compileScript(configuration, paths, environment) ?: return ExitCode.COMPILATION_ERROR
        val scriptConstructor = getScriptConstructor(scriptClass)

        try {
            try {
                scriptConstructor.newInstance(*arrayOf<Any>(scriptArgs.toTypedArray()))
            }
            finally {
                // NB: these lines are required (see KT-9546) but aren't covered by tests
                System.out.flush()
                System.err.flush()
            }
        }
        catch (e: Throwable) {
            reportExceptionFromScript(e)
            return ExitCode.SCRIPT_EXECUTION_ERROR
        }

        return ExitCode.OK
    }

    private fun reportExceptionFromScript(exception: Throwable) {
        // expecting InvocationTargetException from constructor invocation with cause that describes the actual cause
        val stream = System.err
        val cause = exception.cause
        if (exception !is InvocationTargetException || cause == null) {
            exception.printStackTrace(stream)
            return
        }
        stream.println(cause)
        val fullTrace = cause.stackTrace
        val relevantEntries = fullTrace.size - exception.stackTrace.size
        for (i in 0..relevantEntries - 1) {
            stream.println("\tat " + fullTrace[i])
        }
    }

    private fun getScriptConstructor(scriptClass: Class<*>): Constructor<*> =
            scriptClass.getConstructor(Array<String>::class.java)

    fun compileScript(configuration: CompilerConfiguration, paths: KotlinPaths, environment: KotlinCoreEnvironment): Class<*>? =
            compileScript( {
                               val classPaths = arrayListOf(paths.runtimePath.toURI().toURL())
                               configuration.jvmClasspathRoots.mapTo(classPaths) { it.toURI().toURL() }
                               URLClassLoader(classPaths.toTypedArray())
                           },
                           environment)

    fun compileScript( parentClassLoader: ClassLoader, environment: KotlinCoreEnvironment): Class<*>? = compileScript({ parentClassLoader }, environment)

    private inline fun compileScript(
            makeParentClassLoader: () -> ClassLoader,
            environment: KotlinCoreEnvironment): Class<*>? {
        val state = analyzeAndGenerate(environment) ?: return null

        val classLoader: GeneratedClassLoader
        try {
            classLoader = GeneratedClassLoader(state.factory, makeParentClassLoader(), null)

            val script = environment.getSourceFiles()[0].script ?: error("Script must be parsed")
            return classLoader.loadClass(script.fqName.asString())
        }
        catch (e: Exception) {
            throw RuntimeException("Failed to evaluate script: " + e, e)
        }
    }

    fun analyzeAndGenerate(environment: KotlinCoreEnvironment): GenerationState? {
        val result = analyze(environment, null) ?: return null

        if (!result.shouldGenerateCode) return null

        result.throwIfError()

        return generate(environment, environment.configuration, result, environment.getSourceFiles(), null)
    }

    private fun analyze(environment: KotlinCoreEnvironment, targetDescription: String?): AnalysisResult? {
        val collector = environment.messageCollector

        val analysisStart = PerformanceCounter.currentTime()
        val analyzerWithCompilerReport = AnalyzerWithCompilerReport(collector)
        analyzerWithCompilerReport.analyzeAndReport(
                environment.getSourceFiles(), object : AnalyzerWithCompilerReport.Analyzer {
            override fun analyze(): AnalysisResult {
                val sharedTrace = CliLightClassGenerationSupport.NoScopeRecordCliBindingTrace()
                val moduleContext =
                        TopDownAnalyzerFacadeForJVM.createContextWithSealedModule(environment.project, environment.configuration)

                return TopDownAnalyzerFacadeForJVM.analyzeFilesWithJavaIntegration(
                        moduleContext,
                        environment.getSourceFiles(),
                        sharedTrace,
                        environment.configuration,
                        JvmPackagePartProvider(environment)
                )
            }

            override fun reportEnvironmentErrors() {
                reportRuntimeConflicts(collector, environment.configuration.jvmClasspathRoots)
            }
        })

        val analysisNanos = PerformanceCounter.currentTime() - analysisStart

        val sourceLinesOfCode = environment.sourceLinesOfCode
        val numberOfFiles = environment.getSourceFiles().size
        val time = TimeUnit.NANOSECONDS.toMillis(analysisNanos)
        val speed = sourceLinesOfCode.toFloat() * 1000 / time

        val message = "ANALYZE: $numberOfFiles files ($sourceLinesOfCode lines) ${targetDescription ?: ""}" +
                      "in $time ms - ${"%.3f".format(speed)} loc/s"

        K2JVMCompiler.reportPerf(environment.configuration, message)

        return if (analyzerWithCompilerReport.hasErrors()) null else analyzerWithCompilerReport.analysisResult
    }

    private fun generate(
            environment: KotlinCoreEnvironment,
            configuration: CompilerConfiguration,
            result: AnalysisResult,
            sourceFiles: List<KtFile>,
            module: Module?
    ): GenerationState {
        val generationState = GenerationState(
                environment.project,
                ClassBuilderFactories.BINARIES,
                result.moduleDescriptor,
                result.bindingContext,
                sourceFiles,
                configuration,
                GenerationState.GenerateClassFilter.GENERATE_ALL,
                module?.let(::TargetId),
                module?.let { it.getModuleName() },
                module?.let { File(it.getOutputDirectory()) },
                createOutputFilesFlushingCallbackIfPossible(configuration)
        )
        ProgressIndicatorAndCompilationCanceledStatus.checkCanceled()

        val generationStart = PerformanceCounter.currentTime()

        KotlinCodegenFacade.compileCorrectFiles(generationState, CompilationErrorHandler.THROW_EXCEPTION)

        val generationNanos = PerformanceCounter.currentTime() - generationStart
        val desc = if (module != null) "target " + module.getModuleName() + "-" + module.getModuleType() + " " else ""
        val numberOfSourceFiles = sourceFiles.size
        val numberOfLines = environment.countLinesOfCode(sourceFiles)
        val time = TimeUnit.NANOSECONDS.toMillis(generationNanos)
        val speed = numberOfLines.toFloat() * 1000 / time
        val message = "GENERATE: $numberOfSourceFiles files ($numberOfLines lines) ${desc}in $time ms - ${"%.3f".format(speed)} loc/s"

        K2JVMCompiler.reportPerf(environment.configuration, message)
        ProgressIndicatorAndCompilationCanceledStatus.checkCanceled()

        AnalyzerWithCompilerReport.reportDiagnostics(
                FilteredJvmDiagnostics(
                        generationState.collectedExtraJvmDiagnostics,
                        result.bindingContext.diagnostics
                ),
                environment.messageCollector
        )

        AnalyzerWithCompilerReport.reportBytecodeVersionErrors(
                generationState.extraJvmDiagnosticsTrace.bindingContext, environment.messageCollector
        )

        ProgressIndicatorAndCompilationCanceledStatus.checkCanceled()
        return generationState
    }

    private fun checkKotlinPackageUsage(environment: KotlinCoreEnvironment, files: Collection<KtFile>): Boolean {
        if (environment.configuration.getBoolean(CLIConfigurationKeys.ALLOW_KOTLIN_PACKAGE)) {
            return true
        }
        val messageCollector = environment.configuration.get(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
        val kotlinPackage = FqName.topLevel(Name.identifier("kotlin"))
        files.forEach {
            if (it.packageFqName.isSubpackageOf(kotlinPackage)) {
                messageCollector.report(CompilerMessageSeverity.ERROR,
                                        "Only the Kotlin standard library is allowed to use the 'kotlin' package",
                                        MessageUtil.psiElementToMessageLocation(it.packageDirective!!))
                return false
            }
        }
        return true
    }

    private val KotlinCoreEnvironment.messageCollector: MessageCollector
        get() = configuration.getNotNull(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY)

    private fun reportRuntimeConflicts(messageCollector: MessageCollector, jvmClasspathRoots: List<File>) {
        fun String.removeIdeaVersionSuffix(): String {
            val versionIndex = indexOfAny(arrayListOf("-IJ", "-Idea"))
            return if (versionIndex >= 0) substring(0, versionIndex) else this
        }

        val runtimes = jvmClasspathRoots.map { it.canonicalFile }.filter { it.name == PathUtil.KOTLIN_JAVA_RUNTIME_JAR && it.exists() }

        val runtimeVersions = runtimes.map {
            JarUtil.getJarAttribute(it, Attributes.Name.IMPLEMENTATION_VERSION).orEmpty().removeIdeaVersionSuffix()
        }

        if (runtimeVersions.toSet().size > 1) {
            messageCollector.report(CompilerMessageSeverity.ERROR,
                                    "Conflicting versions of Kotlin runtime on classpath: " + runtimes.joinToString { it.path },
                                    CompilerMessageLocation.NO_LOCATION)
        }
    }
}

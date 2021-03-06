package se.ansman.kotshi

import com.google.auto.service.AutoService
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSetMultimap
import com.google.common.collect.Multimaps
import com.google.common.collect.SetMultimap
import com.squareup.kotlinpoet.classinspector.elements.ElementsClassInspector
import com.squareup.kotlinpoet.metadata.specs.ClassInspector
import net.ltgt.gradle.incap.IncrementalAnnotationProcessor
import net.ltgt.gradle.incap.IncrementalAnnotationProcessorType.AGGREGATING
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.Filer
import javax.annotation.processing.ProcessingEnvironment
import javax.annotation.processing.Processor
import javax.annotation.processing.RoundEnvironment
import javax.lang.model.SourceVersion
import javax.lang.model.element.Element
import javax.lang.model.element.PackageElement
import javax.lang.model.element.TypeElement
import javax.lang.model.util.Elements
import javax.lang.model.util.SimpleElementVisitor6

@AutoService(Processor::class)
@IncrementalAnnotationProcessor(AGGREGATING)
class KotshiProcessor : AbstractProcessor() {
    private lateinit var elements: Elements
    private lateinit var classInspector: ClassInspector
    private lateinit var steps: ImmutableList<out ProcessingStep>

    override fun getSupportedSourceVersion(): SourceVersion = SourceVersion.latestSupported()

    private fun initSteps(): Iterable<ProcessingStep> {
        val adapters: MutableList<GeneratedAdapter> = mutableListOf()
        return listOf(
            AdaptersProcessingStep(
                processor = this,
                classInspector = classInspector,
                messager = processingEnv.messager,
                filer = processingEnv.filer,
                adapters = adapters,
                elements = processingEnv.elementUtils,
                sourceVersion = processingEnv.sourceVersion
            ),
            FactoryProcessingStep(
                processor = this,
                messager = processingEnv.messager,
                filer = processingEnv.filer,
                types = processingEnv.typeUtils,
                elements = processingEnv.elementUtils,
                sourceVersion = processingEnv.sourceVersion,
                adapters = adapters
            )
        )
    }

    @Synchronized
    override fun init(processingEnv: ProcessingEnvironment) {
        super.init(processingEnv)
        elements = processingEnv.elementUtils
        classInspector = ElementsClassInspector.create(elements, processingEnv.typeUtils)
        steps = ImmutableList.copyOf(initSteps())
    }

    private fun getSupportedAnnotationClasses(): Set<Class<out Annotation>> =
        steps.flatMapTo(mutableSetOf()) { it.annotations }

    /**
     * Returns the set of supported annotation types as a  collected from registered
     * [processing steps][ProcessingStep].
     */
    override fun getSupportedAnnotationTypes(): Set<String> =
        getSupportedAnnotationClasses().mapTo(mutableSetOf()) { it.canonicalName }

    override fun process(annotations: Set<TypeElement>, roundEnv: RoundEnvironment): Boolean {
        if (!roundEnv.processingOver()) {
            process(validElements(roundEnv), roundEnv)
        }
        return false
    }

    private fun validElements(roundEnv: RoundEnvironment): ImmutableSetMultimap<Class<out Annotation>, Element> {
        val validElements = ImmutableSetMultimap.builder<Class<out Annotation>, Element>()
        for (annotationClass in getSupportedAnnotationClasses()) {
            validElements.putAll(annotationClass, roundEnv.getElementsAnnotatedWith(annotationClass))
        }
        return validElements.build()
    }

    /** Processes the valid elements, including those previously deferred by each step.  */
    private fun process(validElements: ImmutableSetMultimap<Class<out Annotation>, Element>, roundEnv: RoundEnvironment) {
        for (step in steps) {
            val stepElements = Multimaps.filterKeys(validElements) { it in step.annotations }
            if (!stepElements.isEmpty) {
                step.process(stepElements, roundEnv)
            }
        }
    }

    interface ProcessingStep {
        val annotations: Set<Class<out Annotation>>
        fun process(elementsByAnnotation: SetMultimap<Class<out Annotation>, Element>, roundEnv: RoundEnvironment)
    }

    abstract class GeneratingProcessingStep : ProcessingStep {
        protected abstract val filer: Filer
        protected abstract val processor: KotshiProcessor
    }

    private data class ElementName(private val kind: Kind, val name: String) {
        private enum class Kind {
            PACKAGE_NAME,
            TYPE_NAME
        }

        companion object {
            /**
             * An [ElementName] for a package.
             */
            internal fun forPackageName(packageName: String): ElementName = ElementName(Kind.PACKAGE_NAME, packageName)

            /**
             * An [ElementName] for a type.
             */
            internal fun forTypeName(typeName: String): ElementName = ElementName(Kind.TYPE_NAME, typeName)

        }
    }
}


/**
 * Returns the nearest enclosing [TypeElement] to the current element, throwing
 * an [IllegalArgumentException] if the provided [Element] is a
 * [PackageElement] or is otherwise not enclosed by a type.
 */
private fun getEnclosingType(element: Element): TypeElement {
    return element.accept(object : SimpleElementVisitor6<TypeElement, Void>() {
        override fun defaultAction(e: Element, p: Void?): TypeElement = e.enclosingElement.accept(this, p)

        override fun visitType(e: TypeElement, p: Void?): TypeElement = e

        override fun visitPackage(e: PackageElement, p: Void?): Nothing {
            throw IllegalArgumentException()
        }
    }, null)
}
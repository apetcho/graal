/*
 * Copyright (c) 2017, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.oracle.svm.hosted.substitute;

import static com.oracle.svm.core.annotate.RecomputeFieldValue.Kind.ArrayBaseOffset;
import static com.oracle.svm.core.annotate.RecomputeFieldValue.Kind.ArrayIndexScale;
import static com.oracle.svm.core.annotate.RecomputeFieldValue.Kind.ArrayIndexShift;
import static com.oracle.svm.core.annotate.RecomputeFieldValue.Kind.FieldOffset;
import static com.oracle.svm.core.annotate.RecomputeFieldValue.Kind.StaticFieldBase;
import static org.graalvm.compiler.nodes.graphbuilderconf.InlineInvokePlugin.InlineInfo.createStandardInlineInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.graalvm.compiler.api.replacements.SnippetReflectionProvider;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.DebugContext.Builder;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.graph.iterators.NodeIterable;
import org.graalvm.compiler.java.GraphBuilderPhase;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.FrameState;
import org.graalvm.compiler.nodes.Invoke;
import org.graalvm.compiler.nodes.InvokeWithExceptionNode;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.calc.SignExtendNode;
import org.graalvm.compiler.nodes.calc.SubNode;
import org.graalvm.compiler.nodes.graphbuilderconf.GraphBuilderConfiguration;
import org.graalvm.compiler.nodes.graphbuilderconf.GraphBuilderConfiguration.Plugins;
import org.graalvm.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import org.graalvm.compiler.nodes.graphbuilderconf.InlineInvokePlugin;
import org.graalvm.compiler.nodes.graphbuilderconf.InvocationPlugins;
import org.graalvm.compiler.nodes.java.LoadFieldNode;
import org.graalvm.compiler.nodes.java.MethodCallTargetNode;
import org.graalvm.compiler.nodes.java.StoreFieldNode;
import org.graalvm.compiler.nodes.util.ConstantFoldUtil;
import org.graalvm.compiler.options.Option;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.compiler.phases.OptimisticOptimizations;
import org.graalvm.compiler.phases.common.CanonicalizerPhase;
import org.graalvm.compiler.phases.tiers.HighTierContext;
import org.graalvm.compiler.phases.util.Providers;
import org.graalvm.nativeimage.ImageSingletons;

import com.oracle.graal.pointsto.infrastructure.SubstitutionProcessor;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.graal.pointsto.phases.NoClassInitializationPlugin;
import com.oracle.graal.pointsto.util.GraalAccess;
import com.oracle.svm.core.ParsingReason;
import com.oracle.svm.core.annotate.RecomputeFieldValue;
import com.oracle.svm.core.annotate.RecomputeFieldValue.Kind;
import com.oracle.svm.core.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.option.HostedOptionKey;
import com.oracle.svm.core.option.SubstrateOptionsParser;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.FallbackFeature;
import com.oracle.svm.hosted.FeatureImpl.DuringAnalysisAccessImpl;
import com.oracle.svm.hosted.ImageClassLoader;
import com.oracle.svm.hosted.SVMHost;
import com.oracle.svm.hosted.classinitialization.ClassInitializerGraphBuilderPhase;
import com.oracle.svm.hosted.phases.ConstantFoldLoadFieldPlugin;
import com.oracle.svm.hosted.snippets.ReflectionPlugins;
import com.oracle.svm.util.LogUtils;

import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.PrimitiveConstant;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;

@AutomaticallyRegisteredFeature
class AutomaticSubstitutionFeature implements InternalFeature {

    @Override
    public void duringAnalysis(DuringAnalysisAccess access) {
        DuringAnalysisAccessImpl accessImpl = (DuringAnalysisAccessImpl) access;
        UnsafeAutomaticSubstitutionProcessor automaticSubstitutions = accessImpl.getHostVM().getAutomaticSubstitutionProcessor();
        automaticSubstitutions.processComputedValueFields(accessImpl);
    }
}

/**
 * This class tries to registered automatic substitutions for field offset, array base, array index
 * scale and array index shift unsafe computations.
 */
public class UnsafeAutomaticSubstitutionProcessor extends SubstitutionProcessor {

    private static final int BASIC_LEVEL = 1;
    private static final int INFO_LEVEL = 2;
    private static final int DEBUG_LEVEL = 3;

    static class Options {

        @Option(help = "Unsafe automatic substitutions logging level: Disabled=0, Basic=1, Info=2, Debug=3.)")//
        static final HostedOptionKey<Integer> UnsafeAutomaticSubstitutionsLogLevel = new HostedOptionKey<>(BASIC_LEVEL);
    }

    private final AnnotationSubstitutionProcessor annotationSubstitutions;
    private final Map<ResolvedJavaField, ComputedValueField> fieldSubstitutions;

    private final List<ResolvedJavaType> suppressWarnings;

    private static ResolvedJavaType resolvedUnsafeClass;
    private static ResolvedJavaType resolvedSunMiscUnsafeClass;

    private ResolvedJavaMethod unsafeStaticFieldOffsetMethod;
    private ResolvedJavaMethod unsafeStaticFieldBaseMethod;
    private ResolvedJavaMethod unsafeObjectFieldOffsetFieldMethod;
    private ResolvedJavaMethod sunMiscUnsafeObjectFieldOffsetMethod;
    private ResolvedJavaMethod unsafeObjectFieldOffsetClassStringMethod;
    private ResolvedJavaMethod unsafeArrayBaseOffsetMethod;
    private ResolvedJavaMethod unsafeArrayIndexScaleMethod;
    private ResolvedJavaMethod integerNumberOfLeadingZerosMethod;

    private HashSet<ResolvedJavaMethod> neverInlineSet = new HashSet<>();
    private HashSet<ResolvedJavaMethod> noCheckedExceptionsSet = new HashSet<>();

    private Plugins plugins;

    private final OptionValues options;
    private final SnippetReflectionProvider snippetReflection;

    public UnsafeAutomaticSubstitutionProcessor(OptionValues options, AnnotationSubstitutionProcessor annotationSubstitutions, SnippetReflectionProvider snippetReflection) {
        this.options = options;
        this.snippetReflection = snippetReflection;
        this.annotationSubstitutions = annotationSubstitutions;
        this.fieldSubstitutions = new ConcurrentHashMap<>();
        this.suppressWarnings = new ArrayList<>();
    }

    public void init(ImageClassLoader loader, MetaAccessProvider originalMetaAccess) {
        ResolvedJavaMethod atomicIntegerFieldUpdaterNewUpdaterMethod;
        ResolvedJavaMethod atomicLongFieldUpdaterNewUpdaterMethod;
        ResolvedJavaMethod atomicReferenceFieldUpdaterNewUpdaterMethod;

        ResolvedJavaMethod fieldSetAccessibleMethod;
        ResolvedJavaMethod fieldGetMethod;

        try {

            Method fieldSetAccessible = Field.class.getMethod("setAccessible", boolean.class);
            fieldSetAccessibleMethod = originalMetaAccess.lookupJavaMethod(fieldSetAccessible);
            neverInlineSet.add(fieldSetAccessibleMethod);

            Method fieldGet = Field.class.getMethod("get", Object.class);
            fieldGetMethod = originalMetaAccess.lookupJavaMethod(fieldGet);
            neverInlineSet.add(fieldGetMethod);

            /*
             * Various factory methods in VarHandle query the array base/index or field offsets.
             * There is no need to analyze these calls because VarHandle accesses are intrinsified
             * to simple array and field access nodes in IntrinsifyMethodHandlesInvocationPlugin.
             */
            for (Method method : loader.findClassOrFail("java.lang.invoke.VarHandles").getDeclaredMethods()) {
                neverInlineSet.add(originalMetaAccess.lookupJavaMethod(method));
            }

            Class<?> unsafeClass;
            Class<?> sunMiscUnsafeClass;

            try {
                sunMiscUnsafeClass = Class.forName("sun.misc.Unsafe");
                unsafeClass = Class.forName("jdk.internal.misc.Unsafe");
            } catch (ClassNotFoundException cnfe) {
                throw VMError.shouldNotReachHere(cnfe);
            }

            resolvedUnsafeClass = originalMetaAccess.lookupJavaType(unsafeClass);
            resolvedSunMiscUnsafeClass = originalMetaAccess.lookupJavaType(sunMiscUnsafeClass);

            Method unsafeStaticFieldOffset = unsafeClass.getMethod("staticFieldOffset", Field.class);
            unsafeStaticFieldOffsetMethod = originalMetaAccess.lookupJavaMethod(unsafeStaticFieldOffset);
            noCheckedExceptionsSet.add(unsafeStaticFieldOffsetMethod);
            neverInlineSet.add(unsafeStaticFieldOffsetMethod);

            Method unsafeStaticFieldBase = unsafeClass.getMethod("staticFieldBase", Field.class);
            unsafeStaticFieldBaseMethod = originalMetaAccess.lookupJavaMethod(unsafeStaticFieldBase);
            noCheckedExceptionsSet.add(unsafeStaticFieldBaseMethod);
            neverInlineSet.add(unsafeStaticFieldBaseMethod);

            Method unsafeObjectFieldOffset = unsafeClass.getMethod("objectFieldOffset", java.lang.reflect.Field.class);
            unsafeObjectFieldOffsetFieldMethod = originalMetaAccess.lookupJavaMethod(unsafeObjectFieldOffset);
            noCheckedExceptionsSet.add(unsafeObjectFieldOffsetFieldMethod);
            neverInlineSet.add(unsafeObjectFieldOffsetFieldMethod);

            Method unsafeObjectClassStringOffset = unsafeClass.getMethod("objectFieldOffset", java.lang.Class.class, String.class);
            unsafeObjectFieldOffsetClassStringMethod = originalMetaAccess.lookupJavaMethod(unsafeObjectClassStringOffset);
            noCheckedExceptionsSet.add(unsafeObjectFieldOffsetClassStringMethod);
            neverInlineSet.add(unsafeObjectFieldOffsetClassStringMethod);

            /*
             * The JDK checks for hidden classes and record classes in sun.misc.Unsafe before
             * delegating to jdk.internal.misc.Unsafe. When inlined, the checks make control flow
             * too complex to detect offset field assignments.
             */
            Method sunMiscUnsafeObjectFieldOffset = sunMiscUnsafeClass.getMethod("objectFieldOffset", java.lang.reflect.Field.class);
            sunMiscUnsafeObjectFieldOffsetMethod = originalMetaAccess.lookupJavaMethod(sunMiscUnsafeObjectFieldOffset);
            noCheckedExceptionsSet.add(sunMiscUnsafeObjectFieldOffsetMethod);
            neverInlineSet.add(sunMiscUnsafeObjectFieldOffsetMethod);

            Method unsafeArrayBaseOffset = unsafeClass.getMethod("arrayBaseOffset", java.lang.Class.class);
            unsafeArrayBaseOffsetMethod = originalMetaAccess.lookupJavaMethod(unsafeArrayBaseOffset);
            noCheckedExceptionsSet.add(unsafeArrayBaseOffsetMethod);
            neverInlineSet.add(unsafeArrayBaseOffsetMethod);

            Method unsafeArrayIndexScale = unsafeClass.getMethod("arrayIndexScale", java.lang.Class.class);
            unsafeArrayIndexScaleMethod = originalMetaAccess.lookupJavaMethod(unsafeArrayIndexScale);
            noCheckedExceptionsSet.add(unsafeArrayIndexScaleMethod);
            neverInlineSet.add(unsafeArrayIndexScaleMethod);

            Method integerNumberOfLeadingZeros = java.lang.Integer.class.getMethod("numberOfLeadingZeros", int.class);
            integerNumberOfLeadingZerosMethod = originalMetaAccess.lookupJavaMethod(integerNumberOfLeadingZeros);
            neverInlineSet.add(integerNumberOfLeadingZerosMethod);

            Method atomicIntegerFieldUpdaterNewUpdater = java.util.concurrent.atomic.AtomicIntegerFieldUpdater.class.getMethod("newUpdater", Class.class, String.class);
            atomicIntegerFieldUpdaterNewUpdaterMethod = originalMetaAccess.lookupJavaMethod(atomicIntegerFieldUpdaterNewUpdater);
            neverInlineSet.add(atomicIntegerFieldUpdaterNewUpdaterMethod);

            Method atomicLongFieldUpdaterNewUpdater = java.util.concurrent.atomic.AtomicLongFieldUpdater.class.getMethod("newUpdater", Class.class, String.class);
            atomicLongFieldUpdaterNewUpdaterMethod = originalMetaAccess.lookupJavaMethod(atomicLongFieldUpdaterNewUpdater);
            neverInlineSet.add(atomicLongFieldUpdaterNewUpdaterMethod);

            Method atomicReferenceFieldUpdaterNewUpdater = java.util.concurrent.atomic.AtomicReferenceFieldUpdater.class.getMethod("newUpdater", Class.class, Class.class, String.class);
            atomicReferenceFieldUpdaterNewUpdaterMethod = originalMetaAccess.lookupJavaMethod(atomicReferenceFieldUpdaterNewUpdater);
            neverInlineSet.add(atomicReferenceFieldUpdaterNewUpdaterMethod);

        } catch (NoSuchMethodException e) {
            throw VMError.shouldNotReachHere(e);
        }

        /*
         * Create the GraphBuilderPhase which builds the graph for the static initializers.
         *
         * The builder phase will inline the first level callees to detect cases where the offset
         * computation is performed by methods that wrap over the unsafe API. There are two
         * exceptions:
         *
         * 1. Don't inline the invokes that we are trying to match.
         *
         * 2. Don't inline Atomic*FieldUpdater.newUpdater() methods as they lead to false errors.
         * These methods reach calls to Unsafe.objectFieldOffset() whose value is recomputed by
         * RecomputeFieldValue.Kind.AtomicFieldUpdaterOffset.
         */
        StaticInitializerInlineInvokePlugin inlineInvokePlugin = new StaticInitializerInlineInvokePlugin(neverInlineSet);

        plugins = new Plugins(new InvocationPlugins());
        plugins.appendInlineInvokePlugin(inlineInvokePlugin);
        NoClassInitializationPlugin classInitializationPlugin = new NoClassInitializationPlugin();
        plugins.setClassInitializationPlugin(classInitializationPlugin);

        FallbackFeature fallbackFeature = ImageSingletons.contains(FallbackFeature.class) ? ImageSingletons.lookup(FallbackFeature.class) : null;
        ReflectionPlugins.registerInvocationPlugins(loader, snippetReflection, annotationSubstitutions, classInitializationPlugin, plugins.getInvocationPlugins(), null,
                        ParsingReason.UnsafeSubstitutionAnalysis, fallbackFeature);

        /*
         * Note: ConstantFoldLoadFieldPlugin should not be installed because it will disrupt
         * patterns that we try to match, e.g., like processArrayIndexShiftFromField() which relies
         * on the fact that the array index shift computation can be tracked back to the matching
         * array index scale.
         */

        /*
         * Analyzing certain classes leads to false errors. We disable reporting for those classes
         * by default.
         */
        try {
            suppressWarnings.add(originalMetaAccess.lookupJavaType(Class.forName("sun.security.provider.ByteArrayAccess")));
        } catch (ClassNotFoundException e) {
            throw VMError.shouldNotReachHere(e);
        }
    }

    /**
     * Post-process computed value fields during analysis, e.g, like registering the target field of
     * field offset computation as unsafe accessed. Operations that lookup fields/methods/types in
     * the analysis universe cannot be executed while the substitution is computed. The call to
     * {@link #computeSubstitutions} is made from
     * com.oracle.graal.pointsto.meta.AnalysisUniverse#createType(ResolvedJavaType), before the type
     * is published. Thus if there is a circular dependency between the processed type and one of
     * the fields/methods/types that it needs to access it might lead to a deadlock in
     * {@link AnalysisType} creation. The automatic substitutions for an {@link AnalysisType} are
     * computed just after the type is created but before it is published to other threads so that
     * all threads see the substitutions.
     */
    void processComputedValueFields(DuringAnalysisAccessImpl access) {
        for (ResolvedJavaField field : fieldSubstitutions.values()) {
            if (field instanceof ComputedValueField) {
                ComputedValueField cvField = (ComputedValueField) field;

                switch (cvField.getRecomputeValueKind()) {
                    case FieldOffset:
                        Field targetField = cvField.getTargetField();
                        if (access.registerAsUnsafeAccessed(access.getMetaAccess().lookupJavaField(targetField), cvField)) {
                            access.requireAnalysisIteration();
                        }
                        break;
                }
            }
        }
    }

    private void addSubstitutionField(ResolvedJavaField original, ComputedValueField substitution) {
        assert substitution != null;
        assert !fieldSubstitutions.containsKey(original);
        fieldSubstitutions.put(original, substitution);
    }

    @Override
    public ResolvedJavaField lookup(ResolvedJavaField field) {
        if (fieldSubstitutions.containsKey(field)) {
            return fieldSubstitutions.get(field);
        }
        return field;
    }

    @SuppressWarnings("try")
    public void computeSubstitutions(SVMHost hostVM, ResolvedJavaType hostType) {
        if (hostType.isArray()) {
            return;
        }
        if (!hostVM.getClassInitializationSupport().maybeInitializeAtBuildTime(hostType)) {
            /*
             * The class initializer of this type is executed at run time. The methods in Unsafe are
             * substituted to return the correct value at image runtime, or fail if the field was
             * not registered for unsafe access.
             *
             * Calls to Unsafe.objectFieldOffset() with a constant field parameter are automatically
             * registered for unsafe access in SubstrateGraphBuilderPlugins. While that logic is a
             * bit less powerful compared to the parsing in this class (because this class performs
             * inlining during parsing), it should be sufficient for most cases to automatically
             * perform the unsafe access registration. And if not, the user needs to provide a
             * proper manual configuration file.
             */
            return;
        }

        if (annotationSubstitutions.findFullSubstitution(hostType).isPresent()) {
            /* If the class is substituted clinit will be eliminated, so bail early. */
            reportSkippedSubstitution(hostType);
            return;
        }

        /* Detect field offset computation in static initializers. */
        ResolvedJavaMethod clinit = hostType.getClassInitializer();

        if (clinit != null && clinit.hasBytecodes()) {
            /*
             * Since this analysis is run after the AnalysisType is created at this point the class
             * should already be linked and clinit should be available.
             */
            DebugContext debug = new Builder(options).build();
            try (DebugContext.Scope s = debug.scope("Field offset computation", clinit)) {
                StructuredGraph clinitGraph = getStaticInitializerGraph(clinit, debug);

                for (Invoke invoke : clinitGraph.getInvokes()) {
                    if (invoke.callTarget() instanceof MethodCallTargetNode) {
                        if (isInvokeTo(invoke, unsafeStaticFieldBaseMethod)) {
                            processUnsafeFieldComputation(hostType, invoke, StaticFieldBase);
                        } else if (isInvokeTo(invoke, unsafeObjectFieldOffsetFieldMethod) || isInvokeTo(invoke, sunMiscUnsafeObjectFieldOffsetMethod) ||
                                        isInvokeTo(invoke, unsafeStaticFieldOffsetMethod)) {
                            processUnsafeFieldComputation(hostType, invoke, FieldOffset);
                        } else if (isInvokeTo(invoke, unsafeObjectFieldOffsetClassStringMethod)) {
                            processUnsafeObjectFieldOffsetClassStringInvoke(hostType, invoke);
                        } else if (isInvokeTo(invoke, unsafeArrayBaseOffsetMethod)) {
                            processUnsafeArrayBaseOffsetInvoke(hostType, invoke);
                        } else if (isInvokeTo(invoke, unsafeArrayIndexScaleMethod)) {
                            processUnsafeArrayIndexScaleInvoke(hostType, invoke, clinitGraph);
                        }
                    }
                }

            } catch (Throwable e) {
                throw debug.handle(e);
            }
        }

    }

    /**
     * Process calls to <code>Unsafe.objectFieldOffset(Field)</code>,
     * <code>Unsafe.staticFieldOffset(Field)</code> and <code>Unsafe.staticFieldBase(Field)</code>.
     * The matching logic below applies to the following code patterns:
     * <p>
     * <code> static final long fieldOffset = Unsafe.getUnsafe().objectFieldOffset(X.class.getDeclaredField("f")); </code>
     * <p>
     * <code> static final long fieldOffset = Unsafe.getUnsafe().staticFieldOffset(X.class.getDeclaredField("f")); </code>
     * <p>
     * <code> static final long fieldOffset = Unsafe.getUnsafe().staticFieldBase(X.class.getDeclaredField("f")); </code>
     */
    private void processUnsafeFieldComputation(ResolvedJavaType type, Invoke invoke, Kind kind) {
        List<Supplier<String>> unsuccessfulReasons = new ArrayList<>();

        Class<?> targetFieldHolder = null;
        String targetFieldName = null;

        String methodFormat = invoke.callTarget().targetMethod().format("%H.%n(%P)");
        ValueNode fieldArgumentNode = invoke.callTarget().arguments().get(1);
        JavaConstant fieldArgument = nodeAsConstant(fieldArgumentNode);
        if (fieldArgument != null) {
            Field targetField = snippetReflection.asObject(Field.class, fieldArgument);
            if (isValidField(invoke, targetField, unsuccessfulReasons, methodFormat)) {
                targetFieldHolder = targetField.getDeclaringClass();
                targetFieldName = targetField.getName();
            }
        } else {
            unsuccessfulReasons.add(() -> "The argument of " + methodFormat + " is not a constant value or a field load that can be constant-folded.");
        }
        processUnsafeFieldComputation(type, invoke, kind, unsuccessfulReasons, targetFieldHolder, targetFieldName);
    }

    /**
     * Try to extract a {@link JavaConstant} from a {@link ValueNode}. If the node is a
     * {@link LoadFieldNode} it attempts to constant fold it. We manually constant fold just
     * specific nodes instead of globally installing {@link ConstantFoldLoadFieldPlugin} to avoid
     * folding load filed nodes that could disrupt other patterns that we try to match, e.g., like
     * {@link #processArrayIndexShiftFromField(ResolvedJavaType, ResolvedJavaField, Class, StructuredGraph)}.
     */
    private JavaConstant nodeAsConstant(ValueNode node) {
        if (node.isConstant()) {
            return node.asJavaConstant();
        } else if (node instanceof LoadFieldNode) {
            LoadFieldNode loadFieldNode = (LoadFieldNode) node;
            ResolvedJavaField field = loadFieldNode.field();
            JavaConstant receiver = null;
            if (!field.isStatic()) {
                ValueNode receiverNode = loadFieldNode.object();
                if (receiverNode.isConstant()) {
                    receiver = receiverNode.asJavaConstant();
                }
            }
            Providers p = GraalAccess.getOriginalProviders();
            ConstantNode result = ConstantFoldUtil.tryConstantFold(p.getConstantFieldProvider(), p.getConstantReflection(), p.getMetaAccess(),
                            field, receiver, options, loadFieldNode.getNodeSourcePosition());
            if (result != null) {
                return result.asJavaConstant();
            }
        }
        return null;
    }

    private boolean isValidField(Invoke invoke, Field field, List<Supplier<String>> unsuccessfulReasons, String methodFormat) {
        if (field == null) {
            unsuccessfulReasons.add(() -> "The argument of " + methodFormat + " is a null constant.");
            return false;
        }

        boolean valid = true;
        if (isInvokeTo(invoke, sunMiscUnsafeObjectFieldOffsetMethod)) {
            Class<?> declaringClass = field.getDeclaringClass();
            if (declaringClass.isRecord()) {
                unsuccessfulReasons.add(() -> "The argument to " + methodFormat + " is a field of a record.");
                valid = false;
            }
            if (declaringClass.isHidden()) {
                unsuccessfulReasons.add(() -> "The argument to " + methodFormat + " is a field of a hidden class.");
                valid = false;
            }
        }
        return valid;
    }

    /**
     * Process call to <code>Unsafe.objectFieldOffset(Class<?> class, String name)</code>. The
     * matching logic below applies to the following code pattern:
     *
     * <code> static final long fieldOffset = Unsafe.getUnsafe().objectFieldOffset(X.class, "f"); </code>
     */
    private void processUnsafeObjectFieldOffsetClassStringInvoke(ResolvedJavaType type, Invoke unsafeObjectFieldOffsetInvoke) {
        List<Supplier<String>> unsuccessfulReasons = new ArrayList<>();

        Class<?> targetFieldHolder = null;
        String targetFieldName = null;

        ValueNode classArgument = unsafeObjectFieldOffsetInvoke.callTarget().arguments().get(1);
        if (classArgument.isConstant()) {
            Class<?> clazz = snippetReflection.asObject(Class.class, classArgument.asJavaConstant());
            if (clazz == null) {
                unsuccessfulReasons.add(() -> "The Class argument of Unsafe.objectFieldOffset(Class, String) is a null constant.");
            } else {
                targetFieldHolder = clazz;
            }
        } else {
            unsuccessfulReasons.add(() -> "The Class argument of Unsafe.objectFieldOffset(Class, String) is not a constant class.");
        }

        ValueNode nameArgument = unsafeObjectFieldOffsetInvoke.callTarget().arguments().get(2);
        if (nameArgument.isConstant()) {
            String fieldName = snippetReflection.asObject(String.class, nameArgument.asJavaConstant());
            if (fieldName == null) {
                unsuccessfulReasons.add(() -> "The String argument of Unsafe.objectFieldOffset(Class, String) is a null String.");
            } else {
                targetFieldName = fieldName;
            }
        } else {
            unsuccessfulReasons.add(() -> "The name argument of Unsafe.objectFieldOffset(Class, String) is not a constant String.");
        }
        processUnsafeFieldComputation(type, unsafeObjectFieldOffsetInvoke, FieldOffset, unsuccessfulReasons, targetFieldHolder, targetFieldName);
    }

    private void processUnsafeFieldComputation(ResolvedJavaType type, Invoke invoke, Kind kind, List<Supplier<String>> unsuccessfulReasons, Class<?> targetFieldHolder, String targetFieldName) {
        assert kind == FieldOffset || kind == StaticFieldBase;
        /*
         * If the value returned by the call to Unsafe.objectFieldOffset() is stored into a field
         * then that must be the offset field.
         */
        SearchResult result = extractValueStoreField(invoke.asNode(), kind, unsuccessfulReasons);

        /* No field, but the value doesn't have illegal usages, ignore. */
        if (result.valueStoreField == null && !result.illegalUseFound) {
            return;
        }

        ResolvedJavaField valueStoreField = result.valueStoreField;
        /*
         * If the target field holder and name, and the offset field were found try to register a
         * substitution.
         */
        if (targetFieldHolder != null && targetFieldName != null && valueStoreField != null) {
            Supplier<ComputedValueField> supplier = () -> new ComputedValueField(valueStoreField, null, kind, targetFieldHolder, targetFieldName, false);
            if (tryAutomaticRecomputation(valueStoreField, kind, supplier)) {
                reportSuccessfulAutomaticRecomputation(kind, valueStoreField, targetFieldHolder.getName() + "." + targetFieldName);
            }
        } else {
            reportUnsuccessfulAutomaticRecomputation(type, valueStoreField, invoke, kind, unsuccessfulReasons);
        }
    }

    /**
     * Process call to <code>Unsafe.arrayBaseOffset(Class)</code>. The matching logic below applies
     * to the following code pattern:
     *
     * <code> static final long arrayBaseOffsets = Unsafe.getUnsafe().arrayBaseOffset(byte[].class); </code>
     */
    private void processUnsafeArrayBaseOffsetInvoke(ResolvedJavaType type, Invoke unsafeArrayBaseOffsetInvoke) {
        SnippetReflectionProvider snippetReflectionProvider = GraalAccess.getOriginalSnippetReflection();

        List<Supplier<String>> unsuccessfulReasons = new ArrayList<>();

        Class<?> arrayClass = null;

        ValueNode arrayClassArgument = unsafeArrayBaseOffsetInvoke.callTarget().arguments().get(1);
        if (arrayClassArgument.isJavaConstant()) {
            arrayClass = snippetReflectionProvider.asObject(Class.class, arrayClassArgument.asJavaConstant());
        } else {
            unsuccessfulReasons.add(() -> "The argument of the call to Unsafe.arrayBaseOffset() is not a constant.");
        }

        /*
         * If the value returned by the call to Unsafe.arrayBaseOffset() is stored into a field then
         * that must be the offset field.
         */
        SearchResult result = extractValueStoreField(unsafeArrayBaseOffsetInvoke.asNode(), ArrayBaseOffset, unsuccessfulReasons);

        ResolvedJavaField offsetField = result.valueStoreField;
        if (arrayClass != null && offsetField != null) {
            Class<?> finalArrayClass = arrayClass;
            Supplier<ComputedValueField> supplier = () -> new ComputedValueField(offsetField, null, ArrayBaseOffset, finalArrayClass, null, true);
            if (tryAutomaticRecomputation(offsetField, ArrayBaseOffset, supplier)) {
                reportSuccessfulAutomaticRecomputation(ArrayBaseOffset, offsetField, arrayClass.getCanonicalName());
            }
        } else {
            /* Don't report a failure if the value doesn't have illegal usages. */
            if (result.illegalUseFound) {
                reportUnsuccessfulAutomaticRecomputation(type, offsetField, unsafeArrayBaseOffsetInvoke, ArrayBaseOffset, unsuccessfulReasons);
            }
        }
    }

    /**
     * Process call to <code>Unsafe.arrayIndexScale(Class)</code>. The matching logic below applies
     * to the following code pattern:
     *
     * <code> static final long byteArrayIndexScale = Unsafe.getUnsafe().arrayIndexScale(byte[].class); </code>
     */
    private void processUnsafeArrayIndexScaleInvoke(ResolvedJavaType type, Invoke unsafeArrayIndexScale, StructuredGraph clinitGraph) {
        SnippetReflectionProvider snippetReflectionProvider = GraalAccess.getOriginalSnippetReflection();

        List<Supplier<String>> unsuccessfulReasons = new ArrayList<>();

        Class<?> arrayClass = null;

        ValueNode arrayClassArgument = unsafeArrayIndexScale.callTarget().arguments().get(1);
        if (arrayClassArgument.isJavaConstant()) {
            arrayClass = snippetReflectionProvider.asObject(Class.class, arrayClassArgument.asJavaConstant());
        } else {
            unsuccessfulReasons.add(() -> "The argument of the call to Unsafe.arrayIndexScale() is not a constant.");
        }

        /*
         * If the value returned by the call to Unsafe.unsafeArrayIndexScale() is stored into a
         * field then that must be the offset field.
         */
        SearchResult result = extractValueStoreField(unsafeArrayIndexScale.asNode(), ArrayIndexScale, unsuccessfulReasons);

        ResolvedJavaField indexScaleField = result.valueStoreField;
        boolean indexScaleComputed = false;
        boolean indexShiftComputed = false;

        if (arrayClass != null) {
            if (indexScaleField != null) {
                Class<?> finalArrayClass = arrayClass;
                Supplier<ComputedValueField> supplier = () -> new ComputedValueField(indexScaleField, null, ArrayIndexScale, finalArrayClass, null, true);
                if (tryAutomaticRecomputation(indexScaleField, ArrayIndexScale, supplier)) {
                    reportSuccessfulAutomaticRecomputation(ArrayIndexScale, indexScaleField, arrayClass.getCanonicalName());
                    indexScaleComputed = true;
                    /* Try substitution for the array index shift computation if present. */
                    indexShiftComputed = processArrayIndexShiftFromField(type, indexScaleField, arrayClass, clinitGraph);
                }
            } else {
                /*
                 * The index scale is not stored into a field, it might be used to compute the index
                 * shift.
                 */
                indexShiftComputed = processArrayIndexShiftFromLocal(type, unsafeArrayIndexScale, arrayClass);
            }
        }
        if (!indexScaleComputed && !indexShiftComputed) {
            /* Don't report a failure if the value doesn't have illegal usages. */
            if (result.illegalUseFound) {
                reportUnsuccessfulAutomaticRecomputation(type, indexScaleField, unsafeArrayIndexScale, ArrayIndexScale, unsuccessfulReasons);
            }
        }
    }

    /**
     * Process array index shift computation which usually follows a call to
     * <code>Unsafe.arrayIndexScale(Class)</code>. The matching logic below applies to the following
     * code pattern:
     *
     * <code>
     *      static final long byteArrayIndexScale = Unsafe.getUnsafe().arrayIndexScale(byte[].class);
     *      static final long byteArrayIndexShift;
     *      static {
     *          if ((byteArrayIndexScale & (byteArrayIndexScale - 1)) != 0) {
     *              throw new Error("data type scale not a power of two");
     *          }
     *          byteArrayIndexShift = 31 - Integer.numberOfLeadingZeros(byteArrayIndexScale);
     *      }
     * </code>
     *
     * It is important that constant folding is not enabled for the byteArrayIndexScale load because
     * it would break the link between the scale and shift computations.
     */
    private boolean processArrayIndexShiftFromField(ResolvedJavaType type, ResolvedJavaField indexScaleField, Class<?> arrayClass, StructuredGraph clinitGraph) {
        for (LoadFieldNode load : clinitGraph.getNodes().filter(LoadFieldNode.class)) {
            if (load.field().equals(indexScaleField)) {
                /*
                 * Try to determine index shift computation without reporting errors, an index scale
                 * field was already found. The case where both scale and shift are computed is
                 * uncommon.
                 */
                if (processArrayIndexShift(type, arrayClass, load, true)) {
                    /*
                     * Return true as soon as an index shift computed from the index scale field is
                     * found. It is very unlikely that there are multiple shift computations from
                     * the same scale.
                     */
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Process array index shift computation which usually follows a call to
     * <code>Unsafe.arrayIndexScale(Class)</code>. The matching logic below applies to the following
     * code pattern:
     *
     * <code>
     *      static final long byteArrayIndexShift;
     *      static {
     *          long byteArrayIndexScale = Unsafe.getUnsafe().arrayIndexScale(byte[].class);
     *          if ((byteArrayIndexScale & (byteArrayIndexScale - 1)) != 0) {
     *              throw new Error("data type scale not a power of two");
     *          }
     *          byteArrayIndexShift = 31 - Integer.numberOfLeadingZeros(byteArrayIndexScale);
     *      }
     * </code>
     */
    private boolean processArrayIndexShiftFromLocal(ResolvedJavaType type, Invoke unsafeArrayIndexScale, Class<?> arrayClass) {
        /* Try to compute index shift. Report errors since the index scale field was not found. */
        return processArrayIndexShift(type, arrayClass, unsafeArrayIndexScale.asNode(), false);
    }

    /** Try to compute the arrayIndexShift. Return true if successful, false otherwise. */
    private boolean processArrayIndexShift(ResolvedJavaType type, Class<?> arrayClass, ValueNode indexScaleValue, boolean silentFailure) {
        NodeIterable<MethodCallTargetNode> loadMethodCallTargetUsages = indexScaleValue.usages().filter(MethodCallTargetNode.class);
        for (MethodCallTargetNode methodCallTarget : loadMethodCallTargetUsages) {
            /* Iterate over all the calls that use the index scale value. */
            if (isInvokeTo(methodCallTarget.invoke(), integerNumberOfLeadingZerosMethod)) {
                /*
                 * Found a call to Integer.numberOfLeadingZeros(int) that uses the array index scale
                 * field. Check if it is used to calculate the array index shift, i.e., log2 of the
                 * array index scale.
                 */

                SearchResult result = null;
                ResolvedJavaField indexShiftField = null;
                List<Supplier<String>> unsuccessfulReasons = new ArrayList<>();
                Invoke numberOfLeadingZerosInvoke = methodCallTarget.invoke();
                NodeIterable<SubNode> numberOfLeadingZerosInvokeSubUsages = numberOfLeadingZerosInvoke.asNode().usages().filter(SubNode.class);
                if (numberOfLeadingZerosInvokeSubUsages.count() == 1) {
                    /*
                     * Found the SubNode. Determine if it computes the array index shift. If so find
                     * the field where the value is stored.
                     */
                    SubNode subNode = numberOfLeadingZerosInvokeSubUsages.first();
                    if (subNodeComputesLog2(subNode, numberOfLeadingZerosInvoke)) {
                        result = extractValueStoreField(subNode, ArrayIndexShift, unsuccessfulReasons);
                        indexShiftField = result.valueStoreField;
                    } else {
                        unsuccessfulReasons.add(() -> "The index array scale value provided by " + indexScaleValue + " is not used to calculate the array index shift.");
                    }
                } else {
                    unsuccessfulReasons.add(() -> "The call to " + methodCallTarget.targetMethod().format("%H.%n(%p)") + " has multiple uses.");
                }

                if (indexShiftField != null) {
                    ResolvedJavaField finalIndexShiftField = indexShiftField;
                    Supplier<ComputedValueField> supplier = () -> new ComputedValueField(finalIndexShiftField, null, ArrayIndexShift, arrayClass, null, true);
                    if (tryAutomaticRecomputation(indexShiftField, ArrayIndexShift, supplier)) {
                        reportSuccessfulAutomaticRecomputation(ArrayIndexShift, indexShiftField, arrayClass.getCanonicalName());
                        return true;
                    }
                } else {
                    if (!silentFailure) {
                        /*
                         * Passing null here for the computedField is ok, there is no actual field
                         * that we can refer to in the error, and we check for null inside the
                         * method.
                         */
                        if (result != null && result.illegalUseFound || !unsuccessfulReasons.isEmpty()) {
                            reportUnsuccessfulAutomaticRecomputation(type, null, numberOfLeadingZerosInvoke, ArrayIndexShift, unsuccessfulReasons);
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Check if the SubNode computes log2 of one of it's operands. The order of operands is not
     * assumed; both permutations are checked.
     */
    private static boolean subNodeComputesLog2(SubNode subNode, Invoke numberOfLeadingZerosInvokeNode) {
        ValueNode xValueNode = subNode.getX();
        ValueNode yValueNode = subNode.getY();

        if (xValueNode.isJavaConstant() && xValueNode.asJavaConstant().getJavaKind() == JavaKind.Int) {
            PrimitiveConstant xValueConstant = (PrimitiveConstant) xValueNode.asJavaConstant();
            if (xValueConstant.asInt() == 31) {
                assert yValueNode.equals(numberOfLeadingZerosInvokeNode.asNode());
                return true;
            }
        }

        if (yValueNode.isJavaConstant() && yValueNode.asJavaConstant().getJavaKind() == JavaKind.Int) {
            PrimitiveConstant yValueConstant = (PrimitiveConstant) yValueNode.asJavaConstant();
            if (yValueConstant.asInt() == 31) {
                assert xValueNode.equals(numberOfLeadingZerosInvokeNode.asNode());
                return true;
            }
        }

        return false;
    }

    /**
     * Encodes the result of the left-hand-side analysis of an unsafe call, i.e., the search for a
     * static final field where the unsafe value may be stored.
     */
    static final class SearchResult {
        /** The field where the value is stored, if found. */
        final ResolvedJavaField valueStoreField;
        /**
         * Uses that can lead to the unsafe value having side effects that we didn't account for are
         * considered illegal.
         */
        final boolean illegalUseFound;

        private SearchResult(ResolvedJavaField valueStoreField, boolean illegalUseFound) {
            this.valueStoreField = valueStoreField;
            this.illegalUseFound = illegalUseFound;
        }

        static SearchResult foundField(ResolvedJavaField offsetField) {
            return new SearchResult(offsetField, false);
        }

        static SearchResult foundIllegalUse() {
            return new SearchResult(null, true);
        }

        static SearchResult didNotFindIllegalUse() {
            return new SearchResult(null, false);
        }
    }

    /**
     * If the value produced by valueNode is stored into a static final field then that field is
     * returned. If the field is either not static or not final the method returns null and the
     * reason is recorded in the unsuccessfulReasons parameter.
     */
    private static SearchResult extractValueStoreField(ValueNode valueNode, Kind substitutionKind, List<Supplier<String>> unsuccessfulReasons) {
        ResolvedJavaField valueStoreField = null;
        boolean illegalUseFound = false;

        /*
         * Cycle through all usages looking for the field where the value may be stored. The search
         * continues until all usages are exhausted or an illegal use is found.
         */
        outer: for (Node valueNodeUsage : valueNode.usages()) {
            if (valueNodeUsage instanceof StoreFieldNode && valueStoreField == null) {
                valueStoreField = ((StoreFieldNode) valueNodeUsage).field();
            } else if (valueNodeUsage instanceof SignExtendNode && valueStoreField == null) {
                SignExtendNode signExtendNode = (SignExtendNode) valueNodeUsage;
                for (Node signExtendNodeUsage : signExtendNode.usages()) {
                    if (signExtendNodeUsage instanceof StoreFieldNode && valueStoreField == null) {
                        valueStoreField = ((StoreFieldNode) signExtendNodeUsage).field();
                    } else if (isAllowedUnsafeValueSink(signExtendNodeUsage)) {
                        continue;
                    } else {
                        illegalUseFound = true;
                        break outer;
                    }
                }
            } else if (isAllowedUnsafeValueSink(valueNodeUsage)) {
                continue;
            } else {
                illegalUseFound = true;
                break;
            }
        }

        if (valueStoreField != null && !illegalUseFound) {
            if (valueStoreField.isStatic() && valueStoreField.isFinal()) {
                /* Success! We found the static final field where this value is stored. */
                return SearchResult.foundField(valueStoreField);
            } else {
                ResolvedJavaField valueStoreFieldFinal = valueStoreField;
                Supplier<String> message = () -> "The field " + valueStoreFieldFinal.format("%H.%n") + ", where the value produced by the " + kindAsString(substitutionKind) +
                                " computation is stored, is not" + (!valueStoreFieldFinal.isStatic() ? " static" : "") + (!valueStoreFieldFinal.isFinal() ? " final" : "") + ".";
                unsuccessfulReasons.add(message);
                /* Value is stored to a non static final field. */
                return SearchResult.foundIllegalUse();
            }
        }

        if (illegalUseFound) {
            /* No static final store field was found and the value has illegal usages. */
            String producer;
            String operation;
            if (valueNode instanceof Invoke) {
                Invoke invokeNode = (Invoke) valueNode;
                producer = "call to " + invokeNode.callTarget().targetMethod().format("%H.%n(%p)");
                operation = "call";
            } else if (valueNode instanceof SubNode) {
                producer = "subtraction operation " + valueNode;
                operation = "subtraction";
            } else {
                throw VMError.shouldNotReachHereUnexpectedInput(valueNode); // ExcludeFromJacocoGeneratedReport
            }
            Supplier<String> message = () -> "Could not determine the field where the value produced by the " + producer +
                            " for the " + kindAsString(substitutionKind) + " computation is stored. The " + operation +
                            " is not directly followed by a field store or by a sign extend node followed directly by a field store. ";
            unsuccessfulReasons.add(message);
            return SearchResult.foundIllegalUse();
        }

        /* No static final store field was found but value does have any illegal usages. */
        return SearchResult.didNotFindIllegalUse();
    }

    /**
     * Determine if the valueNodeUsage parameter is an allowed usage of an offset, indexScale or
     * indexShift unsafe value.
     */
    private static boolean isAllowedUnsafeValueSink(Node valueNodeUsage) {
        if (valueNodeUsage instanceof FrameState) {
            /*
             * The frame state keeps track of the local variables and operand stack at a particular
             * point in the abstract interpretation. This usage can be ignored for the purpose of
             * this analysis.
             */
            return true;
        }
        if (valueNodeUsage instanceof MethodCallTargetNode) {
            /*
             * Passing the value as a parameter to certain methods, like Unsafe methods that read
             * and write memory based on it, is allowed. Passing an unsafe value as a parameter is
             * sound as long as the called method doesn't propagate the value to a dissalowed usage,
             * e.g., like a store to a field that we would then miss.
             */
            MethodCallTargetNode methodCallTarget = (MethodCallTargetNode) valueNodeUsage;
            ResolvedJavaType declaringClass = methodCallTarget.targetMethod().getDeclaringClass();
            if (declaringClass.equals(resolvedUnsafeClass) || declaringClass.equals(resolvedSunMiscUnsafeClass)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Try to register the automatic substitution for a field. Bail if the field was deleted or
     * another substitution is detected.
     *
     * @param field stores the value of the recomputation, i.e., an offset, array idx scale or shift
     */
    private boolean tryAutomaticRecomputation(ResolvedJavaField field, Kind kind, Supplier<ComputedValueField> substitutionSupplier) {
        if (annotationSubstitutions.isDeleted(field)) {
            String conflictingSubstitution = "The field " + field.format("%H.%n") + " is marked as deleted. ";
            reportConflictingSubstitution(field, kind, conflictingSubstitution);
            return false;
        } else {
            ComputedValueField computedValueField = substitutionSupplier.get();
            Field targetField = computedValueField.getTargetField();
            if (targetField != null && annotationSubstitutions.isDeleted(targetField)) {
                String conflictingSubstitution = "The target field of " + field.format("%H.%n") + " is marked as deleted. ";
                reportSkippedSubstitution(field, kind, conflictingSubstitution);
                return false;
            }
            Optional<ResolvedJavaField> annotationSubstitution = annotationSubstitutions.findSubstitution(field);
            if (annotationSubstitution.isPresent()) {
                /* An annotation substitutions detected. */
                ResolvedJavaField substitutionField = annotationSubstitution.get();
                if (substitutionField instanceof ComputedValueField) {
                    ComputedValueField computedSubstitutionField = (ComputedValueField) substitutionField;
                    if (computedSubstitutionField.getRecomputeValueKind().equals(kind)) {
                        if (computedSubstitutionField.getTargetField().equals(computedValueField.getTargetField())) {
                            /*
                             * Skip the warning when the target field of the found manual
                             * substitution differs from the target field of the discovered original
                             * offset computation. This will avoid printing false positives for
                             * substitutions like Target_java_lang_Class_Atomic.*Offset.
                             */
                            reportUnnecessarySubstitution(computedValueField, computedSubstitutionField);
                        }
                        return false;
                    } else if (computedSubstitutionField.getRecomputeValueKind().equals(Kind.None)) {
                        /*
                         * This is essentially an @Alias field. An @Alias for a field with an
                         * automatic recomputed value is allowed but the alias needs to be
                         * overwritten otherwise the value from the original field would be read. To
                         * do this a new recomputed value field is registered in the automatic
                         * substitution processor, which follows the annotation substitution
                         * processor in the substitutions chain. Thus, every time the substitutions
                         * chain is queried for the original field, e.g., in
                         * AnalysisUniverse.lookupAllowUnresolved(JavaField), the alias field is
                         * forwarded to the automatic substitution.
                         */
                        addSubstitutionField(computedSubstitutionField, computedValueField);
                        reportOvewrittenSubstitution(substitutionField, kind, computedSubstitutionField.getAnnotated(), computedSubstitutionField.getRecomputeValueKind());
                        return true;
                    } else {
                        String conflictingSubstitution = "Detected RecomputeFieldValue." + computedSubstitutionField.getRecomputeValueKind() +
                                        " " + computedSubstitutionField.getAnnotated().format("%H.%n") + " substitution field. ";
                        reportConflictingSubstitution(substitutionField, kind, conflictingSubstitution);
                        return false;
                    }
                } else {
                    String conflictingSubstitution = "Detected " + substitutionField.format("%H.%n") + " substitution field. ";
                    reportConflictingSubstitution(substitutionField, kind, conflictingSubstitution);
                    return false;
                }
            } else {
                /* No other substitutions detected. */
                addSubstitutionField(field, computedValueField);
                return true;
            }
        }
    }

    private static void reportSkippedSubstitution(ResolvedJavaType type) {
        if (Options.UnsafeAutomaticSubstitutionsLogLevel.getValue() >= DEBUG_LEVEL) {
            LogUtils.warning("Skipped automatic unsafe substitutions analysis for type " + type.getName() +
                            ". The entire type is substituted, therefore its class initializer is eliminated.");
        }
    }

    private static void reportUnnecessarySubstitution(ResolvedJavaField offsetField, ComputedValueField computedSubstitutionField) {
        if (Options.UnsafeAutomaticSubstitutionsLogLevel.getValue() >= BASIC_LEVEL) {
            Kind kind = computedSubstitutionField.getRecomputeValueKind();
            String kindStr = RecomputeFieldValue.class.getSimpleName() + "." + kind;
            String annotatedFieldStr = computedSubstitutionField.getAnnotated().format("%H.%n");
            String offsetFieldStr = offsetField.format("%H.%n");
            String optionStr = SubstrateOptionsParser.commandArgument(Options.UnsafeAutomaticSubstitutionsLogLevel, "+");
            LogUtils.warning(
                            "Detected unnecessary %s %s substitution field for %s. The annotated field can be removed. This %s computation can be detected automatically. Use option -H:+%s=%s to print all automatically detected substitutions.",
                            kindStr, annotatedFieldStr, offsetFieldStr, kind, optionStr, INFO_LEVEL);
        }
    }

    private static void reportSuccessfulAutomaticRecomputation(Kind substitutionKind, ResolvedJavaField substitutedField, String target) {
        if (Options.UnsafeAutomaticSubstitutionsLogLevel.getValue() >= INFO_LEVEL) {
            String substitutionKindStr = RecomputeFieldValue.class.getSimpleName() + "." + substitutionKind;
            String substitutedFieldStr = substitutedField.format("%H.%n");
            LogUtils.info("%s substitution automatically registered for %s, target element %s.", substitutionKindStr, substitutedFieldStr, target);
        }
    }

    private static void reportOvewrittenSubstitution(ResolvedJavaField offsetField, Kind newKind, ResolvedJavaField overwrittenField, Kind overwrittenKind) {
        if (Options.UnsafeAutomaticSubstitutionsLogLevel.getValue() >= INFO_LEVEL) {
            String newKindStr = RecomputeFieldValue.class.getSimpleName() + "." + newKind;
            String overwrittenKindStr = RecomputeFieldValue.class.getSimpleName() + "." + overwrittenKind;
            String offsetFieldStr = offsetField.format("%H.%n");
            String overwrittenFieldStr = overwrittenField.format("%H.%n");
            LogUtils.info("The %s %s substitution was overwritten. A %s substitution for %s was automatically registered.", overwrittenKindStr, overwrittenFieldStr, newKindStr, offsetFieldStr);
        }
    }

    private static void reportConflictingSubstitution(ResolvedJavaField field, Kind substitutionKind, String conflictingSubstitution) {
        if (Options.UnsafeAutomaticSubstitutionsLogLevel.getValue() >= BASIC_LEVEL) {
            String fieldStr = field.format("%H.%n");
            String substitutionKindStr = RecomputeFieldValue.class.getSimpleName() + "." + substitutionKind;
            LogUtils.warning(
                            "The %s substitution for %s could not be recomputed automatically because a conflicting substitution was detected. Conflicting substitution: %s. Add a %s manual substitution for %s.",
                            substitutionKindStr, fieldStr, conflictingSubstitution, substitutionKindStr, fieldStr);
        }
    }

    private static void reportSkippedSubstitution(ResolvedJavaField field, Kind substitutionKind, String conflictingSubstitution) {
        if (Options.UnsafeAutomaticSubstitutionsLogLevel.getValue() >= BASIC_LEVEL) {
            String fieldStr = field.format("%H.%n");
            String substitutionKindStr = RecomputeFieldValue.class.getSimpleName() + "." + substitutionKind;
            LogUtils.warning("The %s substitution for %s could not be recomputed automatically because a conflicting substitution was detected. Conflicting substitution: %s.",
                            substitutionKindStr, fieldStr, conflictingSubstitution);
        }
    }

    private void reportUnsuccessfulAutomaticRecomputation(ResolvedJavaType type, ResolvedJavaField computedField, Invoke invoke, Kind substitutionKind, List<Supplier<String>> reasons) {
        String msg = "";
        if (Options.UnsafeAutomaticSubstitutionsLogLevel.getValue() >= BASIC_LEVEL) {
            if (!suppressWarningsFor(type) || Options.UnsafeAutomaticSubstitutionsLogLevel.getValue() >= DEBUG_LEVEL) {
                String substitutionKindStr = RecomputeFieldValue.class.getSimpleName() + "." + substitutionKind;
                String invokeStr = invoke.callTarget().targetMethod().format("%H.%n(%p)");

                msg += substitutionKindStr + " automatic substitution failed. ";
                msg += "The automatic substitution registration was attempted because ";
                if (substitutionKind == ArrayIndexShift) {
                    msg += "an " + ArrayIndexScale + " computation followed by a call to " + invokeStr + " ";
                } else {
                    msg += "a call to " + invokeStr + " ";
                }
                msg += "was detected in the static initializer of " + type.toJavaName() + ". ";
                if (computedField != null) {
                    /* If the computed field is null then reasons will contain the details. */
                    msg += "Add a " + substitutionKindStr + " manual substitution for " + computedField.format("%H.%n") + ". ";
                }
                msg += "Detailed failure reason(s): " + reasons.stream().map(s -> s.get()).collect(Collectors.joining(", "));
            }
        }

        if (Options.UnsafeAutomaticSubstitutionsLogLevel.getValue() >= DEBUG_LEVEL) {
            if (suppressWarningsFor(type)) {
                msg += "(This warning is suppressed by default because this type ";
                if (warningsAreWhiteListed(type)) {
                    msg += "is manually added to a white list";
                } else if (isAliased(type)) {
                    msg += "is aliased";
                } else {
                    ResolvedJavaType substitutionType = findFullSubstitutionType(type);
                    msg += "is fully substituted by " + substitutionType.toJavaName();
                }
                msg += ".)";
            }
        }

        if (!msg.isEmpty()) {
            LogUtils.warning(msg);
        }
    }

    private static String kindAsString(Kind substitutionKind) {
        switch (substitutionKind) {
            case FieldOffset:
                return "field offset";
            case StaticFieldBase:
                return "static field base";
            case ArrayBaseOffset:
                return "array base offset";
            case ArrayIndexScale:
                return "array index scale";
            case ArrayIndexShift:
                return "array index shift";
            default:
                throw VMError.shouldNotReachHere("Unexpected substitution kind: " + substitutionKind);
        }
    }

    private boolean suppressWarningsFor(ResolvedJavaType type) {
        return warningsAreWhiteListed(type) || isAliased(type) || findFullSubstitutionType(type) != null;
    }

    private boolean warningsAreWhiteListed(ResolvedJavaType type) {
        return suppressWarnings.contains(type);
    }

    private ResolvedJavaType findFullSubstitutionType(ResolvedJavaType type) {
        Optional<ResolvedJavaType> substTypeOptional = annotationSubstitutions.findFullSubstitution(type);
        return substTypeOptional.orElse(null);
    }

    private boolean isAliased(ResolvedJavaType type) {
        return annotationSubstitutions.isAliased(type);
    }

    private StructuredGraph getStaticInitializerGraph(ResolvedJavaMethod clinit, DebugContext debug) {
        assert clinit.hasBytecodes();

        HighTierContext context = new HighTierContext(GraalAccess.getOriginalProviders(), null, OptimisticOptimizations.NONE);
        StructuredGraph graph = new StructuredGraph.Builder(options, debug)
                        .method(clinit)
                        .recordInlinedMethods(false)
                        .build();
        graph.getGraphState().configureExplicitExceptionsNoDeopt();

        GraphBuilderPhase.Instance builderPhase = new ClassInitializerGraphBuilderPhase(context, GraphBuilderConfiguration.getDefault(plugins).withEagerResolving(true),
                        context.getOptimisticOptimizations());
        builderPhase.apply(graph, context);

        /*
         * We know that the Unsafe methods that we look for don't throw any checked exceptions.
         * Replace the InvokeWithExceptionNode with InvokeNode.
         */
        for (InvokeWithExceptionNode invoke : graph.getNodes(InvokeWithExceptionNode.TYPE)) {
            if (noCheckedExceptionsSet.contains(invoke.callTarget().targetMethod())) {
                invoke.replaceWithInvoke();
            }
        }
        /* Disable canonicalization of LoadFieldNodes to avoid constant folding of unsafe values. */
        CanonicalizerPhase.createWithoutReadCanonicalization().apply(graph, context);

        return graph;
    }

    private static boolean isInvokeTo(Invoke invoke, ResolvedJavaMethod method) {
        if (method == null) {
            return false;
        }
        ResolvedJavaMethod targetMethod = invoke.callTarget().targetMethod();
        return method.equals(targetMethod);
    }

    static class StaticInitializerInlineInvokePlugin implements InlineInvokePlugin {

        static final int maxDepth = 1;
        static final int maxCodeSize = 500;

        private final HashSet<ResolvedJavaMethod> neverInline;

        StaticInitializerInlineInvokePlugin(HashSet<ResolvedJavaMethod> neverInline) {
            this.neverInline = neverInline;
        }

        @Override
        public InlineInfo shouldInlineInvoke(GraphBuilderContext builder, ResolvedJavaMethod original, ValueNode[] arguments) {

            if (neverInline.contains(original)) {
                return InlineInfo.DO_NOT_INLINE_WITH_EXCEPTION;
            }

            if (original.getCode() != null && original.getCodeSize() < maxCodeSize && builder.getDepth() <= maxDepth) {
                return createStandardInlineInfo(original);
            }

            return null;
        }
    }
}

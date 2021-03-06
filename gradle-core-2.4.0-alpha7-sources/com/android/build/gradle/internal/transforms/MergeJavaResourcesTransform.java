/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.build.gradle.internal.transforms;

import static com.google.common.base.Preconditions.checkNotNull;

import com.android.SdkConstants;
import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.build.api.transform.Format;
import com.android.build.api.transform.QualifiedContent;
import com.android.build.api.transform.QualifiedContent.ContentType;
import com.android.build.api.transform.QualifiedContent.Scope;
import com.android.build.api.transform.Transform;
import com.android.build.api.transform.TransformException;
import com.android.build.api.transform.TransformInvocation;
import com.android.build.api.transform.TransformOutputProvider;
import com.android.build.gradle.internal.dsl.PackagingOptions;
import com.android.build.gradle.internal.packaging.PackagingFileAction;
import com.android.build.gradle.internal.packaging.ParsedPackagingOptions;
import com.android.build.gradle.internal.pipeline.ExtendedContentType;
import com.android.build.gradle.internal.pipeline.IncrementalFileMergerTransformUtils;
import com.android.build.gradle.internal.scope.VariantScope;
import com.android.builder.files.FileCacheByPath;
import com.android.builder.merge.DelegateIncrementalFileMergerOutput;
import com.android.builder.merge.FilterIncrementalFileMergerInput;
import com.android.builder.merge.IncrementalFileMerger;
import com.android.builder.merge.IncrementalFileMergerInput;
import com.android.builder.merge.IncrementalFileMergerOutput;
import com.android.builder.merge.IncrementalFileMergerOutputs;
import com.android.builder.merge.IncrementalFileMergerState;
import com.android.builder.merge.MergeOutputWriters;
import com.android.builder.merge.RenameIncrementalFileMergerInput;
import com.android.builder.merge.StreamMergeAlgorithm;
import com.android.builder.merge.StreamMergeAlgorithms;
import com.android.utils.FileUtils;
import com.android.utils.ImmutableCollectors;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Transform to merge all the Java resources.
 *
 * Based on the value of {@link #getInputTypes()} this will either process native libraries
 * or java resources. While native libraries inside jars are technically java resources, they
 * must be handled separately.
 */
public class MergeJavaResourcesTransform extends Transform {

    private static final Pattern JAR_ABI_PATTERN = Pattern.compile("lib/([^/]+)/[^/]+");
    private static final Pattern ABI_FILENAME_PATTERN = Pattern.compile(".*\\.so");

    @NonNull
    private final ParsedPackagingOptions packagingOptions;

    @NonNull
    private final String name;

    @NonNull
    private final Set<Scope> mergeScopes;
    @NonNull
    private final Set<ContentType> mergedType;

    @NonNull
    private final File intermediateDir;

    @NonNull
    private final FileCacheByPath zipCache;

    private final Predicate<String> acceptedPathsPredicate;

    public MergeJavaResourcesTransform(
            @NonNull PackagingOptions packagingOptions,
            @NonNull Set<Scope> mergeScopes,
            @NonNull ContentType mergedType,
            @NonNull String name,
            @NonNull VariantScope variantScope) {
        this.packagingOptions = new ParsedPackagingOptions(packagingOptions);
        this.name = name;
        this.mergeScopes = Sets.immutableEnumSet(mergeScopes);
        this.mergedType = ImmutableSet.of(mergedType);
        this.intermediateDir = variantScope.getIncrementalDir(
                variantScope.getFullVariantName() + "-" + name);

        File cacheDir = new File(intermediateDir, "zip-cache");
        FileUtils.mkdirs(cacheDir);
        this.zipCache = new FileCacheByPath(cacheDir);

        if (mergedType == QualifiedContent.DefaultContentType.RESOURCES) {
            acceptedPathsPredicate =
                    path -> !path.endsWith(SdkConstants.DOT_CLASS)
                            && !path.endsWith(SdkConstants.DOT_NATIVE_LIBS);
        } else if (mergedType == ExtendedContentType.NATIVE_LIBS) {
            acceptedPathsPredicate =
                    path -> {
                        Matcher m = JAR_ABI_PATTERN.matcher(path);

                        // if the ABI is accepted, check the 3rd segment
                        if (m.matches()) {
                            // remove the beginning of the path (lib/<abi>/)
                            String filename = path.substring(5 + m.group(1).length());
                            // and check the filename
                            return ABI_FILENAME_PATTERN.matcher(filename).matches() ||
                                    SdkConstants.FN_GDBSERVER.equals(filename) ||
                                    SdkConstants.FN_GDB_SETUP.equals(filename);
                        }

                        return false;
                    };
        } else {
            throw new UnsupportedOperationException(
                    "mergedType param must be RESOURCES or NATIVE_LIBS");
        }
    }

    @NonNull
    @Override
    public String getName() {
        return name;
    }

    @NonNull
    @Override
    public Set<ContentType> getInputTypes() {
        return mergedType;
    }

    @NonNull
    @Override
    public Set<Scope> getScopes() {
        return mergeScopes;
    }

    @NonNull
    @Override
    public Map<String, Object> getParameterInputs() {
        return ImmutableMap.of(
                "exclude", packagingOptions.getExcludePatterns(),
                "pickFirst", packagingOptions.getPickFirstPatterns(),
                "merge", packagingOptions.getMergePatterns());
    }

    @Override
    public boolean isIncremental() {
        return true;
    }

    /**
     * Obtains the file where incremental state is saved.
     *
     * @return the file, may not exist
     */
    @NonNull
    private File incrementalStateFile() {
        return new File(intermediateDir, "merge-state");
    }

    /**
     * Loads the incremental state.
     *
     * @return {@code null} if the state is not defined
     * @throws IOException failed to load the incremental state
     */
    @Nullable
    private IncrementalFileMergerState loadMergeState() throws IOException {
        File incrementalFile = incrementalStateFile();
        if (!incrementalFile.isFile()) {
            return null;
        }

        try (ObjectInputStream i = new ObjectInputStream(new FileInputStream(incrementalFile))) {
            return (IncrementalFileMergerState) i.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(e);
        }
    }

    /**
     * Save the incremental merge state.
     *
     * @param state the state
     * @throws IOException failed to save the state
     */
    private void saveMergeState(@NonNull IncrementalFileMergerState state) throws IOException {
        File incrementalFile = incrementalStateFile();

        FileUtils.mkdirs(incrementalFile.getParentFile());
        try (ObjectOutputStream o = new ObjectOutputStream(new FileOutputStream(incrementalFile))) {
            o.writeObject(state);
        }
    }

    @Override
    public void transform(@NonNull TransformInvocation invocation)
            throws IOException, TransformException {

        TransformOutputProvider outputProvider = invocation.getOutputProvider();
        checkNotNull(outputProvider, "Missing output object for transform " + getName());

        boolean full = false;
        IncrementalFileMergerState state = loadMergeState();
        if (state == null || !invocation.isIncremental()) {
            /*
             * This is a full build.
             */
            state = new IncrementalFileMergerState();
            outputProvider.deleteAll();
            full = true;
        }

        List<Runnable> cacheUpdates = new ArrayList<>();

        Map<IncrementalFileMergerInput, QualifiedContent> contentMap = new HashMap<>();
        List<IncrementalFileMergerInput> inputs =
                new ArrayList<>(
                        IncrementalFileMergerTransformUtils.toInput(
                                invocation,
                                zipCache,
                                cacheUpdates,
                                full,
                                contentMap));

        /*
         * In an ideal world, we could just send the inputs to the file merger. However, in the
         * real world we live in, things are more complicated :)
         *
         * We need to:
         *
         * 1. We need to bring inputs that refer to the project scope before the other inputs.
         * 2. Prefix libraries that come from directories with "lib/".
         * 3. Filter all inputs to remove anything not accepted by acceptedPathsPredicate neither
         * by packagingOptions.
         */

        // Sort inputs to move project scopes to the start.
        inputs.sort((i0, i1) -> {
            int v0 = contentMap.get(i0).getScopes().contains(Scope.PROJECT)? 0 : 1;
            int v1 = contentMap.get(i1).getScopes().contains(Scope.PROJECT)? 0 : 1;
            return v0 - v1;
        });

        // Prefix libraries with "lib/" if we're doing libraries.
        assert mergedType.size() == 1;
        if (mergedType.iterator().next() == ExtendedContentType.NATIVE_LIBS) {
            inputs = inputs.stream()
                    .map(i -> {
                        QualifiedContent qc = contentMap.get(i);
                        if (qc.getFile().isDirectory()) {
                            i = new RenameIncrementalFileMergerInput(
                                    i, s -> "lib/" + s, s -> s.substring("lib/".length()));
                            contentMap.put(i, qc);
                        }

                        return i;
                    })
                    .collect(Collectors.toList());
        }

        // Filter inputs.
        Predicate<String> inputFilter =
                acceptedPathsPredicate.and(
                        path -> packagingOptions.getAction(path) != PackagingFileAction.EXCLUDE);
        inputs = inputs.stream()
                .map(i -> {
                    IncrementalFileMergerInput i2 =
                            new FilterIncrementalFileMergerInput(i, inputFilter);
                    contentMap.put(i2, contentMap.get(i));
                    return i2;
                })
                .collect(Collectors.toList());


        File outputDir =
                outputProvider.getContentLocation(
                        "main",
                        getOutputTypes(),
                        getScopes(),
                        Format.DIRECTORY);


        /*
         * Create the algorithm used by the merge transform. This algorithm decides on which
         * algorithm to delegate to depending on the packaging option of the path. By default it
         * requires just one file (no merging).
         */
        StreamMergeAlgorithm mergeTransformAlgorithm = StreamMergeAlgorithms.select(path -> {
                    PackagingFileAction packagingAction = packagingOptions.getAction(path);
                    switch (packagingAction) {
                        case EXCLUDE:
                            // Should have been excluded from the input.
                            throw new AssertionError();
                        case PICK_FIRST:
                            return StreamMergeAlgorithms.pickFirst();
                        case MERGE:
                            return StreamMergeAlgorithms.concat();
                        case NONE:
                            return StreamMergeAlgorithms.acceptOnlyOne();
                        default:
                            throw new AssertionError();
                    }
                });

        /*
         * Create an output that uses the algorithm. This is not the final output because,
         * unfortunately, we still have the complexity of the project scope overriding other scopes
         * to solve.
         */
        IncrementalFileMergerOutput baseOutput =
                IncrementalFileMergerOutputs.fromAlgorithmAndWriter(
                        mergeTransformAlgorithm, MergeOutputWriters.toDirectory(outputDir));

        /*
         * We need a custom output to handle the case in which the same path appears in multiple
         * inputs and the action is NONE, but only one input is actually PROJECT. In this specific
         * case we will ignore all other inputs.
         */

        Set<IncrementalFileMergerInput> projectInputs =
                contentMap.keySet().stream()
                        .filter(i -> contentMap.get(i).getScopes().contains(Scope.PROJECT))
                        .collect(Collectors.toSet());

        IncrementalFileMergerOutput output = new DelegateIncrementalFileMergerOutput(baseOutput) {
            @Override
            public void create(
                    @NonNull String path,
                    @NonNull List<IncrementalFileMergerInput> inputs) {
                super.create(path, filter(path, inputs));
            }

            @Override
            public void update(
                    @NonNull String path,
                    @NonNull List<String> prevInputNames,
                    @NonNull List<IncrementalFileMergerInput> inputs) {
                super.update(path, prevInputNames, filter(path, inputs));
            }

            @Override
            public void remove(@NonNull String path) {
                super.remove(path);
            }

            @NonNull
            private ImmutableList<IncrementalFileMergerInput> filter(
                    @NonNull String path,
                    @NonNull List<IncrementalFileMergerInput> inputs) {
                PackagingFileAction packagingAction = packagingOptions.getAction(path);
                if (packagingAction == PackagingFileAction.NONE
                        && inputs.stream().anyMatch(projectInputs::contains)) {
                    inputs = inputs.stream()
                            .filter(projectInputs::contains)
                            .collect(ImmutableCollectors.toImmutableList());
                }

                return ImmutableList.copyOf(inputs);
            }
        };

        state = IncrementalFileMerger.merge(ImmutableList.copyOf(inputs), output, state);
        saveMergeState(state);

        cacheUpdates.forEach(Runnable::run);
    }
}

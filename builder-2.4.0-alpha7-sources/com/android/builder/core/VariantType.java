/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.builder.core;

import com.android.annotations.NonNull;
import com.android.builder.model.AndroidProject;
import com.android.builder.model.ArtifactMetaData;
import com.google.common.collect.ImmutableList;
import com.google.wireless.android.sdk.stats.GradleBuildVariant;

/**
 * Type of a variant.
 */
public enum VariantType {
    DEFAULT(false, GradleBuildVariant.VariantType.APPLICATION),
    INSTANTAPP(false, GradleBuildVariant.VariantType.INSTANTAPP),
    LIBRARY(true, GradleBuildVariant.VariantType.LIBRARY),
    ATOM(true, GradleBuildVariant.VariantType.ATOM),
    ANDROID_TEST(
            "androidTest",
            "AndroidTest",
            true,
            AndroidProject.ARTIFACT_ANDROID_TEST,
            ArtifactMetaData.TYPE_ANDROID,
            GradleBuildVariant.VariantType.ANDROID_TEST),
    UNIT_TEST(
            "test",
            "UnitTest",
            false,
            AndroidProject.ARTIFACT_UNIT_TEST,
            ArtifactMetaData.TYPE_JAVA,
            GradleBuildVariant.VariantType.UNIT_TEST),
    ;

    public static ImmutableList<VariantType> getTestingTypes() {
        ImmutableList.Builder<VariantType> result = ImmutableList.builder();
        for (VariantType variantType : values()) {
            if (variantType.isForTesting()) {
                result.add(variantType);
            }
        }
        return result.build();
    }

    private final boolean mIsForTesting;
    private final String mPrefix;
    private final String mSuffix;
    private final boolean isSingleBuildType;
    private final String mArtifactName;
    private final int mArtifactType;
    private final boolean exportsDataBindingClassList;
    private final GradleBuildVariant.VariantType mAnalyticsVariantType;

    /** App, Library or Atom variant. */
    VariantType(boolean exportsDataBindingClassList,
            GradleBuildVariant.VariantType analyticsVariantType) {
        this.mIsForTesting = false;
        this.mPrefix = "";
        this.mSuffix = "";
        this.mArtifactName = AndroidProject.ARTIFACT_MAIN;
        this.mArtifactType = ArtifactMetaData.TYPE_ANDROID;
        this.isSingleBuildType = false;
        this.exportsDataBindingClassList = exportsDataBindingClassList;
        this.mAnalyticsVariantType = analyticsVariantType;
    }

    /** Testing variant. */
    VariantType(
            String prefix,
            String suffix,
            boolean isSingleBuildType,
            String artifactName,
            int artifactType,
            GradleBuildVariant.VariantType analyticsVariantType) {
        this.mArtifactName = artifactName;
        this.mArtifactType = artifactType;
        this.mIsForTesting = true;
        this.mPrefix = prefix;
        this.mSuffix = suffix;
        this.isSingleBuildType = isSingleBuildType;
        this.exportsDataBindingClassList = false;
        this.mAnalyticsVariantType = analyticsVariantType;
    }

    /**
     * Returns true if the variant is automatically generated for testing purposed, false
     * otherwise.
     */
    public boolean isForTesting() {
        return mIsForTesting;
    }

    /**
     * Returns prefix used for naming source directories. This is an empty string in
     * case of non-testing variants and a camel case string otherwise, e.g. "androidTest".
     */
    @NonNull
    public String getPrefix() {
        return mPrefix;
    }

    /**
     * Returns suffix used for naming Gradle tasks. This is an empty string in
     * case of non-testing variants and a camel case string otherwise, e.g. "AndroidTest".
     */
    @NonNull
    public String getSuffix() {
        return mSuffix;
    }

    /**
     * Returns the name used in the builder model for artifacts that correspond to this variant
     * type.
     */
    @NonNull
    public String getArtifactName() {
        return mArtifactName;
    }

    /**
     * Returns the artifact type used in the builder model.
     */
    public int getArtifactType() {
        return mArtifactType;
    }

    /**
     * Whether the artifact type supports only a single build type.
     */
    public boolean isSingleBuildType() {
        return isSingleBuildType;
    }

    /**
     * Whether the artifact type should export the data binding class list.
     */
    public boolean isExportDataBindingClassList() {
        return exportsDataBindingClassList;
    }

    /**
     * Returns the corresponding variant type used by the analytics system.
     */
    public GradleBuildVariant.VariantType getAnalyticsVariantType() {
        return mAnalyticsVariantType;
    }
}

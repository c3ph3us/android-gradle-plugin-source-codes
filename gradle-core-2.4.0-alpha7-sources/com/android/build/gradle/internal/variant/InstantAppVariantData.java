/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.build.gradle.internal.variant;

import com.android.annotations.NonNull;
import com.android.build.FilterData;
import com.android.build.OutputFile;
import com.android.build.gradle.AndroidConfig;
import com.android.build.gradle.internal.TaskManager;
import com.android.build.gradle.internal.core.GradleVariantConfiguration;
import com.android.builder.core.ErrorReporter;
import com.android.builder.profile.Recorder;
import java.util.Collection;

/** Data about a variant that produces an instantApp bundle. */
public class InstantAppVariantData extends InstallableVariantData<InstantAppVariantOutputData> {

    public InstantAppVariantData(
            @NonNull AndroidConfig androidConfig,
            @NonNull TaskManager taskManager,
            @NonNull GradleVariantConfiguration config,
            @NonNull ErrorReporter errorReporter,
            @NonNull Recorder recorder) {
        super(androidConfig, taskManager, config, errorReporter, recorder);
    }

    @NonNull
    @Override
    protected InstantAppVariantOutputData doCreateOutput(OutputFile.OutputType outputType,
            Collection<FilterData> filters) {
        return new InstantAppVariantOutputData(outputType, filters, this);
    }

    @NonNull
    @Override
    public String getDescription() {
        if (getVariantConfiguration().hasFlavors()) {
            return String.format("%s build for flavor %s",
                    getCapitalizedBuildTypeName(),
                    getCapitalizedFlavorName());
        } else {
            return String.format("%s build", getCapitalizedBuildTypeName());
        }
    }

}

/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.stagelibrary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.streamsets.pipeline.json.ObjectMapperFactory;
import com.streamsets.pipeline.main.RuntimeInfo;
import com.streamsets.pipeline.api.ConfigDef;
import com.streamsets.pipeline.config.ConfigDefinition;
import com.streamsets.pipeline.config.ModelDefinition;
import com.streamsets.pipeline.config.ModelType;
import com.streamsets.pipeline.config.StageDefinition;
import com.streamsets.pipeline.config.StageType;
import com.streamsets.pipeline.container.LocaleInContext;
import com.streamsets.pipeline.container.Utils;
import com.streamsets.pipeline.task.AbstractTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;

public class ClassLoaderStageLibraryTask extends AbstractTask implements StageLibraryTask {
  private static final Logger LOG = LoggerFactory.getLogger(ClassLoaderStageLibraryTask.class);

  private static final String PIPELINE_STAGES_JSON = "PipelineStages.json";

  private final RuntimeInfo runtimeInfo;
  private List<? extends ClassLoader> stageClassLoaders;
  private Map<String, StageDefinition> stageMap;
  private List<StageDefinition> stageList;
  private LoadingCache<Locale, List<StageDefinition>> localizedStageList;
  private ObjectMapper json;

  @Inject
  public ClassLoaderStageLibraryTask(RuntimeInfo runtimeInfo) {
    super("stageLibrary");
    this.runtimeInfo = runtimeInfo;
  }

  @Override
  protected void initTask() {
    stageClassLoaders = runtimeInfo.getStageLibraryClassLoaders();
    json = ObjectMapperFactory.get();
    stageList = new ArrayList<StageDefinition>();
    stageMap = new HashMap<String, StageDefinition>();
    loadStages();
    stageList = ImmutableList.copyOf(stageList);
    stageMap = ImmutableMap.copyOf(stageMap);

    localizedStageList = CacheBuilder.newBuilder().build(new CacheLoader<Locale, List<StageDefinition>>() {
      @Override
      public List<StageDefinition> load(Locale key) throws Exception {
        List<StageDefinition> list = new ArrayList<StageDefinition>();
        for (StageDefinition stage : stageList) {
          list.add(stage.localize(key));
        }
        return list;
      }
    });

  }

  @VisibleForTesting
  void loadStages() {
    if (LOG.isDebugEnabled()) {
      for (ClassLoader cl : stageClassLoaders) {
        LOG.debug("About to load stages from library '{}'", getLibraryName(cl));
      }
    }
    for (ClassLoader cl : stageClassLoaders) {
      String libraryName = getLibraryName(cl);
      LOG.debug("Loading stages from library '{}'", libraryName);
      try {
        Enumeration<URL> resources = cl.getResources(PIPELINE_STAGES_JSON);
        while (resources.hasMoreElements()) {
          Map<String, String> stagesInLibrary = new HashMap<String, String>();

          URL url = resources.nextElement();
          InputStream is = url.openStream();
          StageDefinition[] stages = json.readValue(is, StageDefinition[].class);
          for (StageDefinition stage : stages) {
            stage.setLibrary(libraryName, cl);
            String key = createKey(libraryName, stage.getName(), stage.getVersion());
            LOG.debug("Loaded stage '{}' (library:name:version)", key);
            if (stagesInLibrary.containsKey(key)) {
              throw new IllegalStateException(Utils.format(
                  "Library '{}' contains more than one definition for stage '{}', class '{}' and class '{}'",
                  libraryName, key, stagesInLibrary.get(key), stage.getStageClass()));
            }
            addSystemConfigurations(stage);
            stagesInLibrary.put(key, stage.getClassName());
            stageList.add(stage);
            stageMap.put(key, stage);
          }
        }
      } catch (IOException ex) {
        throw new RuntimeException(Utils.format("Could not load stages definition from '{}', {}", cl, ex.getMessage()),
                                   ex);
      }
    }
  }

  private static final ConfigDefinition REQUIRED_FIELDS_CONFIG = new ConfigDefinition(
      ConfigDefinition.REQUIRED_FIELDS, ConfigDef.Type.MODEL, "Required Fields", "Record required fields",
      null, false, "system", null, new ModelDefinition(ModelType.FIELD_SELECTOR, null, null, null, null));

  private void addSystemConfigurations(StageDefinition stage) {
    if (stage.getType() != StageType.SOURCE) {
      stage.addConfiguration(REQUIRED_FIELDS_CONFIG);
    }
  }

  private String createKey(String library, String name, String version) {
    return library + ":" + name + ":" + version;
  }

  @Override
  public List<StageDefinition> getStages() {
    try {
      return (LocaleInContext.get() == null) ? stageList : localizedStageList.get(LocaleInContext.get());
    } catch (ExecutionException ex) {
      LOG.warn("Error loading locale '{}', {}", LocaleInContext.get(), ex.getMessage(), ex);
      return stageList;
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public StageDefinition getStage(String library, String name, String version) {
    return stageMap.get(createKey(library, name, version));
  }

  private String getLibraryName(ClassLoader cl) {
    String name;
    try {
      Method method = cl.getClass().getMethod("getName");
      name = (String) method.invoke(cl);
    } catch (NoSuchMethodException ex ) {
      name = "default";
    } catch (Exception ex ) {
      throw new RuntimeException(ex);
    }
    return name;
  }

}

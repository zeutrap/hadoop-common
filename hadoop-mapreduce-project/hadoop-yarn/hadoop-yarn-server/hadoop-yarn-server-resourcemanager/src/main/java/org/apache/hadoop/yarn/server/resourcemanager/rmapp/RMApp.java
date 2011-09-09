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

package org.apache.hadoop.yarn.server.resourcemanager.rmapp;

import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationReport;
import org.apache.hadoop.yarn.event.EventHandler;
import org.apache.hadoop.yarn.server.resourcemanager.recovery.ApplicationsStore.ApplicationStore;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttempt;

public interface RMApp extends EventHandler<RMAppEvent>{

  ApplicationId getApplicationId();

  RMAppState getState();

  String getUser();

  float getProgress();

  RMAppAttempt getRMAppAttempt(ApplicationAttemptId appAttemptId);

  String getQueue();

  String getName();

  RMAppAttempt getCurrentAppAttempt();

  ApplicationReport createAndGetApplicationReport();

  ApplicationStore getApplicationStore();

  long getFinishTime();

  long getStartTime();

  String getTrackingUrl();

  StringBuilder getDiagnostics();
}
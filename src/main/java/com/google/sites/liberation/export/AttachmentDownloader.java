/*
 * Copyright (C) 2009 Google Inc.
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

package com.google.sites.liberation.export;

import com.amazonaws.services.s3.AmazonS3Client;
import com.google.gdata.client.sites.SitesService;
import com.google.gdata.data.sites.AttachmentEntry;
import com.google.inject.ImplementedBy;

/**
 * Downloads attachments to a file.
 * 
 * @author bsimon@google.com (Benjamin Simon)
 */
@ImplementedBy(AttachmentDownloaderImpl.class)
interface AttachmentDownloader {
  
  /**
   * Downloads the given attachment to the given file name, using the given
   * SitesService.
   */
  void download(AttachmentEntry attachment, String s3Bucket, AmazonS3Client s3Client, String s3Key, SitesService sitesService);
}

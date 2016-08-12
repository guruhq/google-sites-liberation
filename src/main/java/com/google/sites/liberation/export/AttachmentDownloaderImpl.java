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

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectResult;
import com.google.gdata.client.sites.SitesService;
import com.google.gdata.data.DateTime;
import com.google.gdata.data.MediaContent;
import com.google.gdata.data.OutOfLineContent;
import com.google.gdata.data.media.MediaSource;
import com.google.gdata.data.sites.AttachmentEntry;
import com.google.gdata.util.ServiceException;

/**
 * Implements {@link AttachmentDownloader} to download an attachment
 * to a specified file.
 * 
 * @author bsimon@google.com (Benjamin Simon)
 */
final class AttachmentDownloaderImpl implements AttachmentDownloader {
  
  private static final Logger LOGGER = Logger.getLogger(
      AttachmentDownloaderImpl.class.getCanonicalName());
  
  /**
   * Downloads the given attachment to the given file name.
   */
  @Override
  public void download(AttachmentEntry attachment, String s3Bucket,
      AmazonS3Client s3Client, String s3Key, SitesService sitesService) {
    checkNotNull(attachment);
    checkNotNull(s3Bucket);
    checkNotNull(s3Key);
    MediaContent mediaContent = new MediaContent();
    mediaContent.setUri(((OutOfLineContent) attachment.getContent()).getUri());
    try {
      MediaSource mediaSource = sitesService.getMedia(mediaContent);
      InputStream inStream = mediaSource.getInputStream();
      
      ObjectMetadata metadata = new ObjectMetadata();
      LOGGER.log(Level.SEVERE, "Putting attachment: " + s3Key);
      LOGGER.log(Level.SEVERE, "ContentLength: " + mediaSource.getContentLength());
      metadata.setContentLength(mediaSource.getContentLength());
      LOGGER.log(Level.SEVERE, "ContentType: " + mediaSource.getContentType());
      metadata.setContentType(mediaSource.getContentType());
      DateTime dt = mediaSource.getLastModified();
      metadata.setLastModified(new Date(dt.getValue()));
      LOGGER.log(Level.SEVERE, "LMD: " + dt.getValue());
      
      try {
        LOGGER.log(Level.SEVERE, "Client: " + s3Client.toString());
        PutObjectResult result = s3Client.putObject(s3Bucket, s3Key, inStream, metadata);
        LOGGER.log(Level.SEVERE, "MD5: " + result.getContentMd5());
      }
      catch (AmazonClientException e) {
        LOGGER.log(Level.SEVERE, "S3 Error", e);
      }
      
      //inStream.close();
    } catch (IOException e) {
      LOGGER.log(Level.WARNING, "Error downloading attachment: " 
          + attachment.getTitle().getPlainText(), e);
    } catch (ServiceException e) {
      LOGGER.log(Level.WARNING, "Error downloading attachment: " 
          + attachment.getTitle().getPlainText(), e);
    }
  }
}

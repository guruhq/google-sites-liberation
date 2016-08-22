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

import static com.amazonaws.SDKGlobalConfiguration.ACCESS_KEY_SYSTEM_PROPERTY;
import static com.amazonaws.SDKGlobalConfiguration.SECRET_KEY_SYSTEM_PROPERTY;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.sites.liberation.util.EntryType.ATTACHMENT;
import static com.google.sites.liberation.util.EntryType.getType;
import static com.google.sites.liberation.util.EntryType.isPage;
import static com.google.sites.liberation.util.EntryUtils.getParentId;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.s3.AmazonS3Client;
import com.google.common.collect.Sets;
import com.google.gdata.client.sites.SitesService;
import com.google.gdata.data.sites.AttachmentEntry;
import com.google.gdata.data.sites.BaseContentEntry;
import com.google.gdata.data.sites.BasePageEntry;
import com.google.gdata.data.sites.ContentFeed;
import com.google.gdata.util.ServiceException;
import com.google.gdata.util.common.base.Nullable;
import com.google.inject.Inject;
import com.google.sites.liberation.util.ProgressListener;
import com.google.sites.liberation.util.UrlUtils;

/**
 * Implements {@link SiteExporter} to export an entire Site
 * to a given root folder.
 *
 * @author bsimon@google.com (Benjamin Simon)
 */
final class SiteExporterImpl implements SiteExporter {

  private static final Logger LOGGER = Logger.getLogger(
      SiteExporterImpl.class.getCanonicalName());

  private final AbsoluteLinkConverter linkConverter;
  private final AppendableFactory appendableFactory;
  private final AttachmentDownloader attachmentDownloader;
  private final EntryStoreFactory entryStoreFactory;
  private final FeedProvider feedProvider;
  private final PageExporter pageExporter;
  private final RevisionsExporter revisionsExporter;

  /**
   * Creates a new SiteExporter with the given dependencies.
   */
  @Inject
  SiteExporterImpl(AbsoluteLinkConverter linkConverter,
      AppendableFactory appendableFactory,
      AttachmentDownloader attachmentDownloader,
      EntryStoreFactory entryStoreFactory,
      FeedProvider feedProvider,
      PageExporter pageExporter,
      RevisionsExporter revisionsExporter) {
    this.linkConverter = checkNotNull(linkConverter);
    this.appendableFactory = checkNotNull(appendableFactory);
    this.attachmentDownloader = checkNotNull(attachmentDownloader);
    this.entryStoreFactory = checkNotNull(entryStoreFactory);
    this.feedProvider = checkNotNull(feedProvider);
    this.pageExporter = checkNotNull(pageExporter);
    this.revisionsExporter = checkNotNull(revisionsExporter);
  }

  @Override
  public String exportSite(String host, @Nullable String domain, String webspace,
      boolean exportRevisions, SitesService sitesService, File rootDirectory,
      ProgressListener progressListener, String s3Bucket, String s3Prefix) {
    checkNotNull(host, "host");
    checkNotNull(webspace, "webspace");
    checkNotNull(sitesService, "sitesService");
    checkNotNull(rootDirectory, "rootDirectory");
    checkNotNull(progressListener, "progressListener");
    Set<BaseContentEntry<?>> pages = Sets.newHashSet();
    Set<AttachmentEntry> attachments = Sets.newHashSet();
    EntryStore entryStore = entryStoreFactory.newEntryStore();
    URL feedUrl = UrlUtils.getFeedUrl(host, domain, webspace);
    URL siteUrl = UrlUtils.getSiteUrl(host, domain, webspace);
    String siteName = null;
    try {
      URL siteNameUrl = UrlUtils.getFeedSiteUrl(host, domain, webspace);
      LOGGER.log(Level.INFO, "SiteUrl: " + siteNameUrl);
      BaseContentEntry feed = sitesService.getEntry(siteNameUrl, BaseContentEntry.class);
      if (feed != null) {
        LOGGER.log(Level.INFO, feed.toString());
        siteName = feed.getTitle().getPlainText();
      }
      else {
        LOGGER.log(Level.SEVERE, "Feed is null");
      }
    }
    catch (IOException e) {
      LOGGER.log(Level.SEVERE, "Error getting sitename", e);
    }
    catch (ServiceException e) {
      LOGGER.log(Level.SEVERE, "Error getting sitename", e);
    }

    AmazonS3Client s3Client = new AmazonS3Client();

    progressListener.setStatus("Retrieving site data (this may take a few minutes).");
    Iterable<BaseContentEntry<?>> entries =
        feedProvider.getEntries(feedUrl, sitesService);
    int num = 1;
    for (BaseContentEntry<?> entry : entries) {
      if (entry != null) {
        if (num % 20 == 0) {
          progressListener.setStatus("Retrieved " + num + " entries.");
        }
        entryStore.addEntry(entry);
        if (isPage(entry)) {
          pages.add((BasePageEntry<?>) entry);
        } else if (getType(entry) == ATTACHMENT) {
          // TODO(gk5885): remove extra cast for
          // http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=6302214
          attachments.add((AttachmentEntry) entry);
        }
        else
        {
            progressListener.setStatus("The class of page is not supported!"
                  + "The class of page:" + entry.getClass());
        }
        num++;
      } else {
        LOGGER.log(Level.WARNING, "Error parsing entries!");
      }
    }

    int totalEntries = pages.size() + attachments.size();
    if (totalEntries > 0) {
      int currentEntries = 0;
      for (BaseContentEntry<?> page : pages) {
        progressListener.setStatus("Exporting page: "
            + page.getTitle().getPlainText() + '.');
        linkConverter.convertLinks(page, entryStore, siteUrl, false);
        File relativePath = getPath(page, entryStore);
        if (relativePath != null) {
          File directory = new File(rootDirectory, relativePath.getPath());
          directory.mkdirs();
          String s3Key = s3Prefix + "/" + relativePath.getPath() + "/index.html";
          exportPage(page, directory, entryStore, exportRevisions, s3Client, s3Bucket, s3Key);
          if (exportRevisions) {
            revisionsExporter.exportRevisions(page, entryStore, directory,
                sitesService, siteUrl);
          }
        }
        progressListener.setProgress(((double) ++currentEntries) / totalEntries);
      }
      for (AttachmentEntry attachment : attachments) {
        progressListener.setStatus("Downloading attachment: "
            + attachment.getTitle().getPlainText() + '.');
        downloadAttachment(attachment, entryStore, sitesService, s3Client, s3Bucket, s3Prefix);
        progressListener.setProgress(((double) ++currentEntries) / totalEntries);
      }
      progressListener.setStatus("Export complete.");
    } else {
      progressListener.setStatus("No data returned. "
          + "Can you get anything from " + feedUrl.toString()+".");
    }
    return siteName;
  }

  private void exportPage(BaseContentEntry<?> page, File directory,
      EntryStore entryStore, boolean revisionsExported, AmazonS3Client s3Client,
      String s3Bucket, String s3Key) {
    File file = new File(directory, "index.html");
    Appendable out = null;
    try {
      out = appendableFactory.getAppendable(file);
      pageExporter.exportPage(page, entryStore, out, revisionsExported);
    } catch (IOException e) {
      LOGGER.log(Level.SEVERE, "Failed writing to file: " + file.getPath(), e);
    } finally {
      if (out instanceof Closeable) {
        try {
          ((Closeable) out).close();
        } catch (IOException e) {
          LOGGER.log(Level.SEVERE, "Failed closing file: " + file.getPath(), e);
        }
      }
    }

    LOGGER.log(Level.INFO, "Putting file: " + s3Key);
    s3Client.putObject(s3Bucket, s3Key, file);

  }

  private void downloadAttachment(AttachmentEntry attachment,
      EntryStore entryStore, SitesService sitesService, AmazonS3Client s3Client, String s3Bucket, String s3Prefix) {
    BasePageEntry<?> parent = entryStore.getParent(attachment.getId());
    if (parent != null) {
      File relativePath = getPath(parent, entryStore);
      if (relativePath != null) {
        String s3Key = s3Prefix + "/" + relativePath.getPath() + "/" + attachment.getTitle().getPlainText();
        attachmentDownloader.download(attachment, s3Bucket, s3Client, s3Key, sitesService);
      }
    }
  }

  /**
   * Returns the site-relative folder path corresponding to the given page, or
   * {@code null} if any of the page's ancestors are missing.
   */
  private File getPath(BaseContentEntry<?> entry, EntryStore entryStore) {
    String parentId = getParentId(entry);
    if (parentId == null) {
      return new File(((BasePageEntry<?>) entry).getPageName().getValue());
    }
    BasePageEntry<?> parent = (BasePageEntry<?>) entryStore.getEntry(parentId);
    if (parent == null) {
      return null;
    }
    return new File(getPath(parent, entryStore), ((BasePageEntry<?>) entry).getPageName().getValue());
  }
}

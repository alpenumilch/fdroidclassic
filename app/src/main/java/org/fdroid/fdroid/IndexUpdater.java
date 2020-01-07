/*
 * Copyright (C) 2018 Senecto Limited
 * Copyright (C) 2016 Blue Jay Wireless
 * Copyright (C) 2015-2016 Daniel Martí <mvdan@mvdan.cc>
 * Copyright (C) 2014-2018 Hans-Christoph Steiner <hans@eds.org>
 * Copyright (C) 2014-2016 Peter Serwylo <peter@serwylo.com>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA 02110-1301, USA.
 */

package org.fdroid.fdroid;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;
import org.fdroid.fdroid.data.Apk;
import org.fdroid.fdroid.data.ApkProvider;
import org.fdroid.fdroid.data.App;
import org.fdroid.fdroid.data.AppProvider;
import org.fdroid.fdroid.data.Repo;
import org.fdroid.fdroid.data.RepoPersister;
import org.fdroid.fdroid.data.RepoProvider;
import org.fdroid.fdroid.data.RepoPushRequest;
import org.fdroid.fdroid.data.Schema.RepoTable;
import org.fdroid.fdroid.installer.InstallManagerService;
import org.fdroid.fdroid.installer.InstallerService;
import org.fdroid.fdroid.net.Downloader;
import org.fdroid.fdroid.net.DownloaderFactory;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.CodeSigner;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

// TODO move to org.fdroid.fdroid.updater
// TODO reduce visibility of methods once in .updater package (.e.g tests need it public now)

/**
 * Updates the local database with a repository's app/apk metadata and verifying
 * the JAR signature on the file received from the repository. As an overview:
 * <ul>
 * <li>Download the {@code index.jar}
 * <li>Verify that it is signed correctly and by the correct certificate
 * <li>Parse the {@code index.xml} that is in {@code index.jar}
 * <li>Save the resulting repo, apps, and apks to the database.
 * <li>Process any push install/uninstall requests included in the repository
 * </ul>
 * <b>WARNING</b>: this class is the central piece of the entire security model of
 * FDroid!  Avoid modifying it when possible, if you absolutely must, be very,
 * very careful with the changes that you are making!
 */
public class IndexUpdater {
    private static final String TAG = "IndexUpdater";

    public static final String SIGNED_FILE_NAME = "index.jar";
    public static final String DATA_FILE_NAME = "index.xml";

    final String indexUrl;

    @NonNull
    final Context context;
    @NonNull
    final Repo repo;
    boolean hasChanged;
    private String cacheTag;
    private X509Certificate signingCertFromJar;

    @NonNull
    private final RepoPersister persister;

    /**
     * Updates an app repo as read out of the database into a {@link Repo} instance.
     *
     * @param repo A {@link Repo} read out of the local database
     */
    public IndexUpdater(@NonNull Context context, @NonNull Repo repo) {
        this.context = context;
        this.repo = repo;
        this.persister = new RepoPersister(context, repo);
        this.indexUrl = getIndexUrl(repo);
    }

    protected String getIndexUrl(@NonNull Repo repo) {
        return repo.address + "/index.jar";
    }

    public boolean hasChanged() {
        return hasChanged;
    }

    private Downloader downloadIndex() throws UpdateException {
        Downloader downloader = null;
        try {
            downloader = DownloaderFactory.create(context, indexUrl);
            downloader.setCacheTag(repo.lastetag);
            downloader.setListener(downloadListener);
            downloader.download();

        } catch (IOException e) {
            if (downloader != null && downloader.outputFile != null) {
                if (!downloader.outputFile.delete()) {
                    Log.w(TAG, "Couldn't delete file: " + downloader.outputFile.getAbsolutePath());
                }
            }

            throw new UpdateException("Error getting index file", e);
        } catch (InterruptedException e) {
            // ignored if canceled, the local database just won't be updated
            e.printStackTrace();
        }
        return downloader;
    }

    private ContentValues repoDetailsToSave;
    private String signingCertFromIndexXml;

    protected final ProgressListener downloadListener = new ProgressListener() {
        @Override
        public void onProgress(String urlString, long bytesRead, long totalBytes) {
            UpdateService.reportDownloadProgress(context, IndexUpdater.this, bytesRead, totalBytes);
        }
    };

    protected final ProgressListener processIndexListener = new ProgressListener() {
        @Override
        public void onProgress(String urlString, long bytesRead, long totalBytes) {
            UpdateService.reportProcessIndexProgress(context, IndexUpdater.this, bytesRead, totalBytes);
        }
    };

    protected void notifyProcessingApps(int appsSaved, int totalApps) {
        UpdateService.reportProcessingAppsProgress(context, this, appsSaved, totalApps);
    }

    protected void notifyCommittingToDb() {
        notifyProcessingApps(0, -1);
    }

    private void commitToDb() throws UpdateException {
        Log.i(TAG, "Repo signature verified, saving app metadata to database.");
        notifyCommittingToDb();
        persister.commit(repoDetailsToSave, repo.getId());
    }

    private void assertSigningCertFromXmlCorrect() throws SigningException {

        // no signing cert read from database, this is the first use
        if (repo.signingCertificate == null) {
            verifyAndStoreTOFUCerts(signingCertFromIndexXml, signingCertFromJar);
        }
        verifyCerts(signingCertFromIndexXml, signingCertFromJar);

    }

    /**
     * Update tracking data for the repo represented by this instance (index version, etag,
     * description, human-readable name, etc.  This is not reused in {@link IndexV1Updater}
     * because its too tied up into the old parsing flow in this class.
     */
    private ContentValues prepareRepoDetailsForSaving(String name, String description, int maxAge,
                                                      int version, long timestamp, String icon,
                                                      String[] mirrors, String cacheTag) {
        ContentValues values = new ContentValues();

        values.put(RepoTable.Cols.LAST_UPDATED, Utils.formatTime(new Date(), ""));

        if (repo.lastetag == null || !repo.lastetag.equals(cacheTag)) {
            values.put(RepoTable.Cols.LAST_ETAG, cacheTag);
        }

        if (version != Repo.INT_UNSET_VALUE && version != repo.version) {
            Utils.debugLog(TAG, "Repo specified a new version: from " + repo.version + " to " + version);
            values.put(RepoTable.Cols.VERSION, version);
        }

        if (maxAge != Repo.INT_UNSET_VALUE && maxAge != repo.maxage) {
            Utils.debugLog(TAG, "Repo specified a new maximum age - updated");
            values.put(RepoTable.Cols.MAX_AGE, maxAge);
        }

        if (description != null && !description.equals(repo.description)) {
            values.put(RepoTable.Cols.DESCRIPTION, description);
        }

        if (name != null && !name.equals(repo.name)) {
            values.put(RepoTable.Cols.NAME, name);
        }

        // Always put a timestamp here, even if it is the same. This is because we are dependent
        // on it later on in the process. Specifically, when updating from a HTTP server that
        // doesn't send out etags with its responses, it will trigger a full blown repo update
        // every time, even if all the values in the index are the same (name, description, etc).
        // In such a case, the remainder of the update process will proceed, and ask for this
        // timestamp.
        values.put(RepoTable.Cols.TIMESTAMP, timestamp);

        if (icon != null && !icon.equals(repo.icon)) {
            values.put(RepoTable.Cols.ICON, icon);
        }

        if (mirrors != null && mirrors.length > 0 && !Arrays.equals(mirrors, repo.mirrors)) {
            values.put(RepoTable.Cols.MIRRORS, Utils.serializeCommaSeparatedString(mirrors));
        }

        return values;
    }

    public static class UpdateException extends Exception {

        private static final long serialVersionUID = -4492452418826132803L;

        public UpdateException(String message) {
            super(message);
        }

        public UpdateException(String message, Exception cause) {
            super(message, cause);
        }
    }

    public static class SigningException extends UpdateException {
        public SigningException(String message) {
            super("Repository was not signed correctly: " + message);
        }

        public SigningException(Repo repo, String message) {
            super((repo == null ? "Repository" : repo.name) + " was not signed correctly: " + message);
        }
    }

    /**
     * FDroid's index.jar is signed using a particular format and does not allow lots of
     * signing setups that would be valid for a regular jar.  This validates those
     * restrictions.
     */
    public static X509Certificate getSigningCertFromJar(JarEntry jarEntry) throws SigningException {
        final CodeSigner[] codeSigners = jarEntry.getCodeSigners();
        if (codeSigners == null || codeSigners.length == 0) {
            throw new SigningException("No signature found in index");
        }
        /* we could in theory support more than 1, but as of now we do not */
        if (codeSigners.length > 1) {
            throw new SigningException("index.jar must be signed by a single code signer!");
        }
        List<? extends Certificate> certs = codeSigners[0].getSignerCertPath().getCertificates();
        if (certs.size() != 1) {
            throw new SigningException("index.jar code signers must only have a single certificate!");
        }
        return (X509Certificate) certs.get(0);
    }

    /**
     * A new repo can be added with or without the fingerprint of the signing
     * certificate.  If no fingerprint is supplied, then do a pure TOFU and just
     * store the certificate as valid.  If there is a fingerprint, then first
     * check that the signing certificate in the jar matches that fingerprint.
     */
    private void verifyAndStoreTOFUCerts(String certFromIndexXml, X509Certificate rawCertFromJar)
            throws SigningException {
        if (repo.signingCertificate != null) {
            return; // there is a repo.signingCertificate already, nothing to TOFU
        }

        /* The first time a repo is added, it can be added with the signing certificate's
         * fingerprint.  In that case, check that fingerprint against what is
         * actually in the index.jar itself.  If no fingerprint, just store the
         * signing certificate */
        if (repo.fingerprint != null) {
            String fingerprintFromIndexXml = Utils.calcFingerprint(certFromIndexXml);
            String fingerprintFromJar = Utils.calcFingerprint(rawCertFromJar);
            if (!repo.fingerprint.equalsIgnoreCase(fingerprintFromIndexXml)
                    || !repo.fingerprint.equalsIgnoreCase(fingerprintFromJar)) {
                throw new SigningException(repo, "Supplied certificate fingerprint does not match!");
            }
        } // else - no info to check things are valid, so just Trust On First Use

        Utils.debugLog(TAG, "Saving new signing certificate in the database for " + repo.address);
        ContentValues values = new ContentValues(2);
        values.put(RepoTable.Cols.LAST_UPDATED, Utils.formatDate(new Date(), ""));
        values.put(RepoTable.Cols.SIGNING_CERT, Hasher.hex(rawCertFromJar));
        RepoProvider.Helper.update(context, repo, values);
    }

    /**
     * FDroid works with three copies of the signing certificate:
     * <li>in the downloaded jar</li>
     * <li>in the index XML</li>
     * <li>stored in the local database</li>
     * It would work better removing the copy from the index XML, but it needs to stay
     * there for backwards compatibility since the old TOFU process requires it.  Therefore,
     * since all three have to be present, all three are compared.
     *
     * @param certFromIndexXml the cert written into the header of the index XML
     * @param rawCertFromJar   the {@link X509Certificate} embedded in the downloaded jar
     */
    private void verifyCerts(String certFromIndexXml, X509Certificate rawCertFromJar) throws SigningException {
        // convert binary data to string version that is used in FDroid's database
        String certFromJar = Hasher.hex(rawCertFromJar);

        // repo and repo.signingCertificate must be pre-loaded from the database
        if (TextUtils.isEmpty(repo.signingCertificate)
                || TextUtils.isEmpty(certFromJar)
                || TextUtils.isEmpty(certFromIndexXml)) {
            throw new SigningException(repo, "A empty repo or signing certificate is invalid!");
        }

        // though its called repo.signingCertificate, its actually a X509 certificate
        if (repo.signingCertificate.equals(certFromJar)
                && repo.signingCertificate.equals(certFromIndexXml)
                && certFromIndexXml.equals(certFromJar)) {
            return; // we have a match!
        }
        throw new SigningException(repo, "Signing certificate does not match!");
    }
}
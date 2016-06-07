/*
 * *** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), hosted at https://github.com/gunterze/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * J4Care.
 * Portions created by the Initial Developer are Copyright (C) 2015
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See @authors listed below
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * *** END LICENSE BLOCK *****
 */

package org.dcm4chee.arc.entity;

import org.dcm4che3.util.TagUtils;

import javax.persistence.*;
import java.util.Date;

/**
 * @author Damien Evans <damien.daddy@gmail.com>
 * @author Justin Falk <jfalkmu@gmail.com>
 * @author Gunter Zeilinger <gunterze@gmail.com>
 */
@Entity
@Table(name = "location", indexes = @Index(columnList = "storage_id,status"))
@NamedQueries({
        @NamedQuery(name = Location.FIND_BY_STORAGE_ID_AND_STATUS,
                query = "select l from Location l where l.storageID=?1 and l.status=?2"),
        @NamedQuery(name = Location.FIND_BY_STUDY_PK,
                query = "select l from Location l where l.instance.series.study.pk=?1"),
        @NamedQuery(name = Location.FIND_BY_REJECTION_CODE,
                query = "select l from Location l join l.instance i " +
                        "where i.rejectionNoteCode=?1"),
        @NamedQuery(name = Location.FIND_BY_CONCEPT_NAME_CODE,
                query = "select l from Location l join l.instance i " +
                        "where i.conceptNameCode=?1"),
        @NamedQuery(name = Location.FIND_BY_REJECTION_CODE_BEFORE,
                query = "select l from Location l join l.instance i " +
                        "where i.rejectionNoteCode=?1 and i.updatedTime<?2"),
        @NamedQuery(name = Location.FIND_BY_CONCEPT_NAME_CODE_BEFORE,
                query = "select l from Location l join l.instance i " +
                        "where i.conceptNameCode=?1 and i.updatedTime<?2")
})
public class Location {

    public static final String FIND_BY_STORAGE_ID_AND_STATUS = "Location.FindByStorageIDAndStatus";
    public static final String FIND_BY_STUDY_PK = "Location.FindByStudyPk";
    public static final String FIND_BY_REJECTION_CODE = "Location.FindByRejectionCode";
    public static final String FIND_BY_CONCEPT_NAME_CODE = "Location.FindByConceptNameCode";
    public static final String FIND_BY_REJECTION_CODE_BEFORE = "Location.FindByRejectionCodeBefore";
    public static final String FIND_BY_CONCEPT_NAME_CODE_BEFORE = "Location.FindByConceptNameCodeBefore";

    public enum Status { OK, TO_DELETE, FAILED_TO_DELETE }

    @Id
    @GeneratedValue(strategy=GenerationType.IDENTITY)
    @Column(name = "pk")
    private long pk;

    @Basic(optional = false)
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "created_time", updatable = false)
    private Date createdTime;

    @Basic(optional = false)
    @Column(name = "storage_id", updatable = false)
    private String storageID;

    @Basic(optional = false)
    @Column(name = "storage_path", updatable = false)
    private String storagePath;

    @Basic(optional = false)
    @Column(name = "tsuid", updatable = false)
    private String transferSyntaxUID;

    @Basic(optional = false)
    @Column(name = "object_size", updatable = false)
    private long size;

    @Basic(optional = true)
    @Column(name = "digest", updatable = false)
    private String digest;

    @Basic(optional = false)
    @Enumerated(EnumType.ORDINAL)
    @Column(name = "status", updatable = true)
    private Status status;

    @ManyToOne
    @JoinColumn(name = "instance_fk", updatable = true)
    private Instance instance;

    public static final class Builder {
        private long pk;
        private String storageID;
        private String storagePath;
        private String transferSyntaxUID;
        private long size;
        private String digest;
        private Status status = Status.OK;

        public Builder pk(long pk) {
            this.pk = pk;
            return this;
        }

        public Builder storageID(String storageID) {
            this.storageID = storageID;
            return this;
        }

        public Builder storagePath(String storagePath) {
            this.storagePath = storagePath;
            return this;
        }

        public Builder transferSyntaxUID(String transferSyntaxUID) {
            this.transferSyntaxUID = transferSyntaxUID;
            return this;
        }

        public Builder size(long size) {
            this.size = size;
            return this;
        }

        public Builder digest(String digest) {
            this.digest = digest;
            return this;
        }

        public Builder digest(byte[] digest) {
            return digest(digest != null ? TagUtils.toHexString(digest) : null);
        }

        public Builder status(Status status) {
            this.status = status;
            return this;
        }

        public Location build() {
            return new Location(this);
        }
    }

    public Location() {}

    private Location(Builder builder) {
        pk = builder.pk;
        storageID = builder.storageID;
        storagePath = builder.storagePath;
        transferSyntaxUID = builder.transferSyntaxUID;
        size = builder.size;
        digest = builder.digest;
        status = builder.status;
    }

    @PrePersist
    public void onPrePersist() {
        createdTime = new Date();
    }

    public long getPk() {
        return pk;
    }

    public Date getCreatedTime() {
        return createdTime;
    }

    public String getStorageID() {
        return storageID;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public String getTransferSyntaxUID() {
        return transferSyntaxUID;
    }

    public long getSize() {
        return size;
    }

    public byte[] getDigest() {
        return digest != null ? TagUtils.fromHexString(digest) : null;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Instance getInstance() {
        return instance;
    }

    public void setInstance(Instance instance) {
        this.instance = instance;
    }

    @Override
    public String toString() {
        return "Location[pk=" + pk
                + ", systemID=" + storageID
                + ", path=" + storagePath
                + ", tsuid=" + transferSyntaxUID
                + ", size=" + size
                + ", status=" + status
                + "]";
    }

}

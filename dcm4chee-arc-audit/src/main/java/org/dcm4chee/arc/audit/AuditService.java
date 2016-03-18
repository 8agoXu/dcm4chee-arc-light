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
 * Portions created by the Initial Developer are Copyright (C) 2013
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

package org.dcm4chee.arc.audit;


import org.dcm4che3.audit.*;
import org.dcm4che3.data.*;
import org.dcm4che3.io.DicomOutputStream;
import org.dcm4che3.net.Connection;
import org.dcm4che3.net.Device;
import org.dcm4che3.net.audit.AuditLogger;
import org.dcm4che3.util.StringUtils;
import org.dcm4chee.arc.conf.ArchiveDeviceExtension;
import org.dcm4chee.arc.delete.StudyDeleteContext;
import org.dcm4chee.arc.query.QueryContext;
import org.dcm4chee.arc.retrieve.InstanceLocations;
import org.dcm4chee.arc.retrieve.RetrieveContext;
import org.dcm4chee.arc.store.StoreContext;
import org.dcm4chee.arc.store.StoreSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.security.action.GetPropertyAction;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;

import static java.security.AccessController.doPrivileged;

/**
 * @author Vrinda Nayak <vrinda.nayak@j4care.com>
 * @author Gunter Zeilinger <gunterze@gmail.com>
 * @since Feb 2016
 */
@ApplicationScoped
public class AuditService {
    private static final Logger LOG = LoggerFactory.getLogger(AuditService.class);
    private static final String tmpdir = doPrivileged(new GetPropertyAction("java.io.tmpdir"));
    private static final String noValue = "<none>";

    @Inject
    private Device device;

    @Inject
    private AuditServiceUtils auditServiceUtils;

    private AuditLogger log() {
        return device.getDeviceExtension(AuditLogger.class);
    }

    public String buildAET() {
        String[] aets = device.getApplicationAETitles().toArray(new String[device.getApplicationAETitles().size()]);
        StringBuilder b = new StringBuilder();
        b.append(aets[0]);
        for (int i = 1; i < aets.length; i++)
            b.append(';').append(aets[i]);
        return b.toString();
    }

    public String getLocalHostName() {
        List<Connection> conns = log().getConnections();
        return conns.get(0).getHostname();
    }

    public void aggregateAuditMessage(Path path) throws IOException {
        AuditServiceUtils.EventType eventType = AuditServiceUtils.EventType.fromFile(path);
        String triggerType = String.valueOf(eventType).substring(0, 5);
        switch (triggerType) {
            case "CONN_":
                auditConnectionRejected(path, eventType);
                break;
            case "STORE":
                aggregateStoreOrWADORetrieve(path, eventType);
                break;
            case "WADO_":
                aggregateStoreOrWADORetrieve(path, eventType);
                break;
            case "BEGIN":
                auditRetrieve(path, eventType);
                break;
            case "TRF__":
                auditRetrieve(path, eventType);
                break;
            case "DELET":
                auditInstanceDeletion(path, eventType);
                break;
            case "PERM_":
                auditPermanentDeletion(path, eventType);
                break;
            case "QUERY":
                auditQuery(path, eventType);
                break;
        }
    }

    private void emitAuditMessage(Calendar timestamp, EventIdentification ei, List<ActiveParticipant> apList,
                                  List<ParticipantObjectIdentification> poiList) {
        AuditMessage msg = new AuditMessage();
        msg.setEventIdentification(ei);
        for (ActiveParticipant ap : apList)
            msg.getActiveParticipant().add(ap);
        msg.getAuditSourceIdentification().add(log().createAuditSourceIdentification());
        for (ParticipantObjectIdentification poi : poiList)
            msg.getParticipantObjectIdentification().add(poi);
        try {
            log().write(timestamp, msg);
        } catch (Exception e) {
            LOG.warn("Failed to emit audit message", e);
        }
    }

    public void auditApplicationActivity(AuditServiceUtils.EventType eventType, HttpServletRequest req) {
        List<ActiveParticipant> apList = new ArrayList<>();
        List<ParticipantObjectIdentification> poiList = new ArrayList<>();
        apList.add(AuditMessages.createActiveParticipant(buildAET(), AuditLogger.processID(), null, false,
                getLocalHostName(), AuditMessages.NetworkAccessPointTypeCode.IPAddress, null,
                AuditMessages.RoleIDCode.Application));
        if (req != null) {
            apList.add(AuditMessages.createActiveParticipant(
                    req.getRemoteUser() != null ? req.getRemoteUser() : req.getRemoteAddr(), null, null, true,
                    req.getRemoteAddr(), AuditMessages.NetworkAccessPointTypeCode.IPAddress, null,
                    AuditMessages.RoleIDCode.ApplicationLauncher));
        }
        emitAuditMessage(log().timeStamp(), AuditMessages.createEventIdentification(
                eventType.eventID, eventType.eventActionCode, log().timeStamp(), eventType.outcomeIndicator, null,
                eventType.eventTypeCode), apList, poiList);
    }

    public void spoolInstancesDeleted(StoreContext ctx) {
        ArchiveDeviceExtension arcDev = device.getDeviceExtension(ArchiveDeviceExtension.class);
        boolean auditAggregate = arcDev.isAuditAggregate();
        Path dir = Paths.get(
                auditAggregate ? StringUtils.replaceSystemProperties(arcDev.getAuditSpoolDirectory()) : tmpdir);
        AuditServiceUtils.EventType et = (ctx.getException() != null)
                ? AuditServiceUtils.EventType.DELETE_ERR : AuditServiceUtils.EventType.DELETE_PAS;
        Attributes attrs = ctx.getAttributes();
        try {
            Path file = Files.createTempFile(dir, String.valueOf(et), null);
            boolean append = Files.exists(file);
            if (!append)
                Files.createDirectories(dir);
            try (LineWriter writer = new LineWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND))) {
                writer.writeLine(new AuditServiceUtils.DeleteInfo(ctx));
                HashMap<String, HashSet<String>> sopClassMap = new HashMap<>();
                for (Attributes studyRef : attrs.getSequence(Tag.CurrentRequestedProcedureEvidenceSequence)) {
                    for (Attributes seriesRef : studyRef.getSequence(Tag.ReferencedSeriesSequence)) {
                        for (Attributes sopRef : seriesRef.getSequence(Tag.ReferencedSOPSequence)) {
                            String cuid = sopRef.getString(Tag.ReferencedSOPClassUID);
                            HashSet<String> iuids = sopClassMap.get(cuid);
                            if (iuids == null) {
                                iuids = new HashSet<>();
                                sopClassMap.put(cuid, iuids);
                            }
                            iuids.add(sopRef.getString(Tag.ReferencedSOPInstanceUID));
                        }
                    }
                }
                for (Map.Entry<String, HashSet<String>> entry : sopClassMap.entrySet()) {
                    writer.writeLine(new AuditServiceUtils.DeleteStudyInfo(
                            entry.getKey(), String.valueOf(entry.getValue().size())));
                }
            }
            if (!auditAggregate)
                aggregateAuditMessage(file);
        } catch (Exception e) {
            LOG.warn("Failed to write to Audit Spool File - {} ", e);
        }
    }

    public void spoolStudyDeleted(StudyDeleteContext ctx) {
        ArchiveDeviceExtension arcDev = device.getDeviceExtension(ArchiveDeviceExtension.class);
        boolean auditAggregate = arcDev.isAuditAggregate();
        Path dir = Paths.get(
                auditAggregate ? StringUtils.replaceSystemProperties(arcDev.getAuditSpoolDirectory()) : tmpdir);
        AuditServiceUtils.EventType eventType = (ctx.getException() != null)
                ? AuditServiceUtils.EventType.PERM_DEL_E : AuditServiceUtils.EventType.PERM_DEL_S;
        try {
            Path file = Files.createTempFile(dir, String.valueOf(eventType), null);
            boolean append = Files.exists(file);
            if (!append)
                Files.createDirectories(dir);
            try (LineWriter writer = new LineWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND))) {
                writer.writeLine(new AuditServiceUtils.PermanentDeletionInfo(ctx));
                HashMap<String, HashSet<String>> sopClassMap = new HashMap<>();
                for (org.dcm4chee.arc.entity.Instance i : ctx.getInstances()) {
                    String cuid = i.getSopClassUID();
                    HashSet<String> iuids = sopClassMap.get(cuid);
                    if (iuids == null) {
                        iuids = new HashSet<>();
                        sopClassMap.put(cuid, iuids);
                    }
                    iuids.add(i.getSopInstanceUID());
                }
                for (Map.Entry<String, HashSet<String>> entry : sopClassMap.entrySet()) {
                    writer.writeLine(new AuditServiceUtils.DeleteStudyInfo(
                            entry.getKey(), String.valueOf(entry.getValue().size())));
                }
            }
            if (!auditAggregate)
                aggregateAuditMessage(file);
        } catch (Exception e) {
            LOG.warn("Failed to write to Audit Spool File - {} ", e);
        }
    }

    private void auditPermanentDeletion(Path file, AuditServiceUtils.EventType eventType) throws IOException {
        List<ActiveParticipant> apList = new ArrayList<>();
        List<ParticipantObjectIdentification> poiList = new ArrayList<>();
        AuditServiceUtils.PermanentDeletionInfo pdi;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            pdi = new AuditServiceUtils.PermanentDeletionInfo(reader.readLine());
            apList.add(AuditMessages.createActiveParticipant(buildAET(), AuditLogger.processID(), null, true,
                    getLocalHostName(), AuditMessages.NetworkAccessPointTypeCode.IPAddress, null));
            ParticipantObjectContainsStudy pocs = new ParticipantObjectContainsStudy();
                    pocs.getStudyIDs().add(AuditMessages.createStudyIDs(pdi.getField(AuditServiceUtils.PermanentDeletionInfo.STUDY_UID)));
            HashSet<Accession> acc = new HashSet<>();
            acc.add(AuditMessages.createAccession(pdi.getField(AuditServiceUtils.PermanentDeletionInfo.ACCESSION)));
            String line;
            HashSet<SOPClass> sopC = new HashSet<>();
            while ((line = reader.readLine()) != null) {
                AuditServiceUtils.DeleteStudyInfo dsi = new AuditServiceUtils.DeleteStudyInfo(line);
                dsi.getField(AuditServiceUtils.DeleteStudyInfo.SOPCLASSUID);
                dsi.getField(AuditServiceUtils.DeleteStudyInfo.NUMINSTANCES);
                sopC.add(AuditMessages.createSOPClass(
                        dsi.getField(AuditServiceUtils.DeleteStudyInfo.SOPCLASSUID),
                        Integer.parseInt(dsi.getField(AuditServiceUtils.DeleteStudyInfo.NUMINSTANCES))));
            }
            if (pdi.getField(AuditServiceUtils.PermanentDeletionInfo.STUDY_DATE) != null)
                poiList.add(AuditMessages.createParticipantObjectIdentification(
                    pdi.getField(AuditServiceUtils.PermanentDeletionInfo.STUDY_UID),
                    AuditMessages.ParticipantObjectIDTypeCode.StudyInstanceUID, null, null,
                    AuditMessages.ParticipantObjectTypeCode.SystemObject, AuditMessages.ParticipantObjectTypeCodeRole.Report,
                    null, null, null, acc, null, sopC, null, null, pocs, AuditMessages.createParticipantObjectDetail(
                                "StudyDate", pdi.getField(AuditServiceUtils.PermanentDeletionInfo.STUDY_DATE).getBytes())));
            else
                poiList.add(AuditMessages.createParticipantObjectIdentification(
                    pdi.getField(AuditServiceUtils.PermanentDeletionInfo.STUDY_UID),
                    AuditMessages.ParticipantObjectIDTypeCode.StudyInstanceUID, null, null,
                    AuditMessages.ParticipantObjectTypeCode.SystemObject, AuditMessages.ParticipantObjectTypeCodeRole.Report,
                    null, null, null, acc, null, sopC, null, null, pocs));
            poiList.add(AuditMessages.createParticipantObjectIdentification(
                    pdi.getField(AuditServiceUtils.PermanentDeletionInfo.PATIENT_ID),
                    AuditMessages.ParticipantObjectIDTypeCode.PatientNumber, pdi.getField(AuditServiceUtils.PermanentDeletionInfo.PATIENT_NAME),
                    null, AuditMessages.ParticipantObjectTypeCode.Person, AuditMessages.ParticipantObjectTypeCodeRole.Patient,
                    null, null, null, null, null, null, null, null, null));
        }
        emitAuditMessage(log().timeStamp(), AuditMessages.createEventIdentification(eventType.eventID, eventType.eventActionCode,
                log().timeStamp(), eventType.outcomeIndicator, pdi.getField(AuditServiceUtils.PermanentDeletionInfo.OUTCOME_DESC)),
                apList, poiList);
    }


    private void auditInstanceDeletion(Path path, AuditServiceUtils.EventType eventType) throws IOException {
        List<ActiveParticipant> apList = new ArrayList<>();
        List<ParticipantObjectIdentification> poiList = new ArrayList<>();
        AuditServiceUtils.DeleteInfo deleteInfo;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            deleteInfo = new AuditServiceUtils.DeleteInfo(reader.readLine());
            apList.add(AuditMessages.createActiveParticipant(
                    deleteInfo.getField(AuditServiceUtils.DeleteInfo.REMOTEAET), null, null, eventType.isSource,
                    deleteInfo.getField(AuditServiceUtils.DeleteInfo.REMOTEHOST),
                    AuditMessages.NetworkAccessPointTypeCode.IPAddress, null));
            apList.add(AuditMessages.createActiveParticipant(
                    deleteInfo.getField(AuditServiceUtils.DeleteInfo.LOCALAET),
                    AuditLogger.processID(), null, eventType.isDest, getLocalHostName(),
                    AuditMessages.NetworkAccessPointTypeCode.IPAddress, null));
            ParticipantObjectContainsStudy pocs = new ParticipantObjectContainsStudy();
            pocs.getStudyIDs().add(AuditMessages.createStudyIDs(deleteInfo.getField(AuditServiceUtils.DeleteInfo.STUDYUID)));
            String line;
            HashSet<SOPClass> sopC = new HashSet<>();
            while ((line = reader.readLine()) != null) {
                AuditServiceUtils.DeleteStudyInfo dsi = new AuditServiceUtils.DeleteStudyInfo(line);
                dsi.getField(AuditServiceUtils.DeleteStudyInfo.SOPCLASSUID);
                dsi.getField(AuditServiceUtils.DeleteStudyInfo.NUMINSTANCES);
                sopC.add(AuditMessages.createSOPClass(
                        dsi.getField(AuditServiceUtils.DeleteStudyInfo.SOPCLASSUID),
                        Integer.parseInt(dsi.getField(AuditServiceUtils.DeleteStudyInfo.NUMINSTANCES))));
            }
            if (deleteInfo.getField(AuditServiceUtils.DeleteInfo.STUDY_DATE) != null)
                poiList.add(AuditMessages.createParticipantObjectIdentification(
                    deleteInfo.getField(AuditServiceUtils.DeleteInfo.STUDYUID), AuditMessages.ParticipantObjectIDTypeCode.StudyInstanceUID,
                    null, null, AuditMessages.ParticipantObjectTypeCode.SystemObject,
                    AuditMessages.ParticipantObjectTypeCodeRole.Report, null, null, null, null, null, sopC, null, null,
                        pocs, AuditMessages.createParticipantObjectDetail("StudyDate", deleteInfo.getField(
                                AuditServiceUtils.DeleteInfo.STUDY_DATE).getBytes())));
            else
                poiList.add(AuditMessages.createParticipantObjectIdentification(
                    deleteInfo.getField(AuditServiceUtils.DeleteInfo.STUDYUID), AuditMessages.ParticipantObjectIDTypeCode.StudyInstanceUID,
                    null, null, AuditMessages.ParticipantObjectTypeCode.SystemObject,
                    AuditMessages.ParticipantObjectTypeCodeRole.Report, null, null, null, null, null, sopC, null, null, pocs));
            poiList.add(AuditMessages.createParticipantObjectIdentification(
                    deleteInfo.getField(AuditServiceUtils.DeleteInfo.PATIENTID), AuditMessages.ParticipantObjectIDTypeCode.PatientNumber,
                    deleteInfo.getField(AuditServiceUtils.DeleteInfo.PATIENTNAME), null, AuditMessages.ParticipantObjectTypeCode.Person,
                    AuditMessages.ParticipantObjectTypeCodeRole.Patient, null, null, null, null, null, null, null, null, null));
        }
        emitAuditMessage(log().timeStamp(), AuditMessages.createEventIdentification(
                eventType.eventID, eventType.eventActionCode, log().timeStamp(),
                eventType.outcomeIndicator, deleteInfo.getField(AuditServiceUtils.DeleteInfo.OUTCOME)), apList, poiList);
    }

    public void spoolConnectionRejected(Connection conn, Socket s, Throwable e) {
        ArchiveDeviceExtension arcDev = device.getDeviceExtension(ArchiveDeviceExtension.class);
        boolean auditAggregate = arcDev.isAuditAggregate();
        Path dir = Paths.get(
                auditAggregate ? StringUtils.replaceSystemProperties(arcDev.getAuditSpoolDirectory()) : tmpdir);
        try {
            Path file = Files.createTempFile(dir, String.valueOf(AuditServiceUtils.EventType.CONN__RJCT), null);
            boolean append = Files.exists(file);
            if (!append)
                Files.createDirectories(dir);
            try (LineWriter writer = new LineWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND))) {
                writer.writeLine(new AuditServiceUtils.ConnectionRejectedInfo(conn, s, e));
            }
            if (!auditAggregate)
                aggregateAuditMessage(file);

        } catch (Exception ex) {
            LOG.warn("Failed to write to Audit Spool File - {} ", ex);
        }
    }

    private void auditConnectionRejected(Path file, AuditServiceUtils.EventType eventType) throws IOException {
        List<ActiveParticipant> apList = new ArrayList<>();
        List<ParticipantObjectIdentification> poiList = new ArrayList<>();
        AuditServiceUtils.ConnectionRejectedInfo crInfo;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            crInfo = new AuditServiceUtils.ConnectionRejectedInfo(reader.readLine());
            apList.add(AuditMessages.createActiveParticipant(
                    buildAET(), AuditLogger.processID(), null, false,
                    crInfo.getField(AuditServiceUtils.ConnectionRejectedInfo.LOCAL_ADDR),
                    AuditMessages.NetworkAccessPointTypeCode.IPAddress, null));
            apList.add(AuditMessages.createActiveParticipant(
                    crInfo.getField(AuditServiceUtils.ConnectionRejectedInfo.REMOTE_ADDR), null, null, true,
                    crInfo.getField(AuditServiceUtils.ConnectionRejectedInfo.REMOTE_ADDR),
                    AuditMessages.NetworkAccessPointTypeCode.IPAddress, null));
            poiList.add(AuditMessages.createParticipantObjectIdentification(
                    crInfo.getField(AuditServiceUtils.ConnectionRejectedInfo.REMOTE_ADDR), AuditMessages.ParticipantObjectIDTypeCode.NodeID,
                    null, null, AuditMessages.ParticipantObjectTypeCode.SystemObject, null, null, null, null, null, null,
                    null, null, null, null));
        }
        emitAuditMessage(log().timeStamp(), AuditMessages.createEventIdentification(
                eventType.eventID, eventType.eventActionCode, log().timeStamp(),
                eventType.outcomeIndicator, crInfo.getField(AuditServiceUtils.ConnectionRejectedInfo.OUTCOME_DESC),
                eventType.eventTypeCode), apList, poiList);
    }

    public void spoolQuery(QueryContext ctx) {
        ArchiveDeviceExtension arcDev = device.getDeviceExtension(ArchiveDeviceExtension.class);
        boolean auditAggregate = arcDev.isAuditAggregate();
        Path dir = Paths.get(
                auditAggregate ? StringUtils.replaceSystemProperties(arcDev.getAuditSpoolDirectory()) : tmpdir);
        AuditServiceUtils.EventType eventType = AuditServiceUtils.EventType.forQuery(ctx);
        try {
            Path file = Files.createTempFile(dir, String.valueOf(eventType), null);
            boolean append = Files.exists(file);
            if (!append)
                Files.createDirectories(dir);
            try (BufferedOutputStream out = new BufferedOutputStream(
                    Files.newOutputStream(file, StandardOpenOption.APPEND))) {
                    new DataOutputStream(out).writeUTF(new AuditServiceUtils.QueryInfo(ctx).toString());
                if (ctx.getAssociation() != null) {
                    try (DicomOutputStream dos = new DicomOutputStream(out, UID.ImplicitVRLittleEndian)) {
                        dos.writeDataset(null, ctx.getQueryKeys());
                    } catch (Exception e) {
                        LOG.warn("Failed to create DicomOutputStream : ", e);
                    }
                }
            }
            if (!auditAggregate)
                aggregateAuditMessage(file);
        } catch (Exception e) {
            LOG.warn("Failed to write to Audit Spool File - {} ", e);
        }
    }

    private void auditQuery(Path file, AuditServiceUtils.EventType eventType) throws IOException {
        List<ActiveParticipant> apList = new ArrayList<>();
        List<ParticipantObjectIdentification> poiList = new ArrayList<>();
        AuditServiceUtils.QueryInfo qrInfo;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            qrInfo = new AuditServiceUtils.QueryInfo(new DataInputStream(in).readUTF());
            apList.add(AuditMessages.createActiveParticipant(qrInfo.getField(AuditServiceUtils.QueryInfo.CALLING_AET),
                    null, null, eventType.isSource, qrInfo.getField(AuditServiceUtils.QueryInfo.REMOTE_HOST),
                    AuditMessages.NetworkAccessPointTypeCode.IPAddress, null, eventType.source));
            apList.add(AuditMessages.createActiveParticipant(qrInfo.getField(AuditServiceUtils.QueryInfo.CALLED_AET),
                    AuditLogger.processID(), null, eventType.isDest, getLocalHostName(),
                    AuditMessages.NetworkAccessPointTypeCode.IPAddress, null, eventType.destination));
            if (!qrInfo.getField(AuditServiceUtils.QueryInfo.PATIENT_ID).equals(noValue))
                poiList.add(AuditMessages.createParticipantObjectIdentification(
                        qrInfo.getField(AuditServiceUtils.QueryInfo.PATIENT_ID), AuditMessages.ParticipantObjectIDTypeCode.PatientNumber,
                        null, null, AuditMessages.ParticipantObjectTypeCode.Person, AuditMessages.ParticipantObjectTypeCodeRole.Patient,
                        null, null, null, null, null, null, null, null, null));
            if (String.valueOf(eventType).equals(String.valueOf(AuditServiceUtils.EventType.QUERY_QIDO)))
                poiList.add(AuditMessages.createParticipantObjectIdentification(
                        null, AuditMessages.ParticipantObjectIDTypeCode.SOPClassUID, null,
                        qrInfo.getField(AuditServiceUtils.QueryInfo.QUERY_STRING).getBytes(),
                        AuditMessages.ParticipantObjectTypeCode.SystemObject, AuditMessages.ParticipantObjectTypeCodeRole.Query,
                        null, null, null, null, null, null, null, null, null,
                        AuditMessages.createParticipantObjectDetail(
                                "QueryEncoding", String.valueOf(StandardCharsets.UTF_8).getBytes())));
            else {
                byte[] buffer = new byte[(int) Files.size(file)];
                int len = in.read(buffer);
                byte[] data = new byte[len];
                System.arraycopy(buffer, 0, data, 0, len);
                poiList.add(AuditMessages.createParticipantObjectIdentification(
                        qrInfo.getField(AuditServiceUtils.QueryInfo.SOPCLASSUID), AuditMessages.ParticipantObjectIDTypeCode.SOPClassUID, null,
                        data, AuditMessages.ParticipantObjectTypeCode.SystemObject,
                        AuditMessages.ParticipantObjectTypeCodeRole.Query,
                        null, null, null, null, null, null, null, null, null,
                        AuditMessages.createParticipantObjectDetail(
                                "TransferSyntax", UID.ImplicitVRLittleEndian.getBytes())));
            }
        }
        emitAuditMessage(log().timeStamp(), AuditMessages.createEventIdentification(
                eventType.eventID, eventType.eventActionCode, log().timeStamp(),
                eventType.outcomeIndicator, null), apList, poiList);
    }

    public void spoolInstanceStored(StoreContext ctx) {
        ArchiveDeviceExtension arcDev = device.getDeviceExtension(ArchiveDeviceExtension.class);
        boolean auditAggregate = arcDev.isAuditAggregate();
        Path dir = Paths.get(
                auditAggregate ? StringUtils.replaceSystemProperties(arcDev.getAuditSpoolDirectory()) : tmpdir);
        AuditServiceUtils.EventType eventType = AuditServiceUtils.EventType.forInstanceStored(ctx);
        if (eventType == null)
            return; // no audit message for duplicate received instance
        StoreSession session = ctx.getStoreSession();
        Attributes attrs = ctx.getAttributes();
        Path file = dir.resolve(
                eventType + session.getCallingAET() + '-' + session.getCalledAET() + '-' + ctx.getStudyInstanceUID());
        boolean append = Files.exists(file);
        try {
            if (!append)
                Files.createDirectories(dir);
            try (LineWriter writer = new LineWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    append ? StandardOpenOption.APPEND : StandardOpenOption.CREATE_NEW))) {
                if (!append) {
                    writer.writeLine(new AuditServiceUtils.PatientStudyInfo(ctx, attrs));
                }
                writer.writeLine(new AuditServiceUtils.InstanceInfo(ctx, attrs));
            }
            if (!auditAggregate)
                aggregateAuditMessage(file);
        } catch (Exception e) {
            LOG.warn("Failed to write to Audit Spool File - {} ", file, e);
        }
    }

    public void spoolWADORetrieve(RetrieveContext ctx){
        AuditServiceUtils.EventType aggregationType = AuditServiceUtils.EventType.forWADORetrieve(ctx);
        HttpServletRequest req = ctx.getHttpRequest();
        Collection<InstanceLocations> il = ctx.getMatches();
        Attributes attrs = new Attributes();
        for (InstanceLocations i : il) {
            attrs = i.getAttributes();
        }
        ArchiveDeviceExtension arcDev = device.getDeviceExtension(ArchiveDeviceExtension.class);
        boolean auditAggregate = arcDev.isAuditAggregate();
        Path dir = Paths.get(
                auditAggregate ? StringUtils.replaceSystemProperties(arcDev.getAuditSpoolDirectory()) : tmpdir);
        Path file = dir.resolve(
                aggregationType + req.getRemoteAddr() + '-' + ctx.getLocalAETitle() + '-' + ctx.getStudyInstanceUIDs()[0]);
        boolean append = Files.exists(file);
        try {
            if (!append)
                Files.createDirectories(dir);
            try (LineWriter writer = new LineWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    append ? StandardOpenOption.APPEND : StandardOpenOption.CREATE_NEW))) {
                if (!append) {
                    writer.writeLine(new AuditServiceUtils.PatientStudyInfo(ctx, attrs));
                }
                writer.writeLine(new AuditServiceUtils.InstanceInfo(ctx, attrs));
            }
            if (!auditAggregate)
                aggregateAuditMessage(file);
        } catch (Exception ioe) {
            LOG.warn("Failed write to Audit Spool File - {} ", file, ioe);
        }
    }

    private void aggregateStoreOrWADORetrieve(Path path, AuditServiceUtils.EventType eventType) throws IOException {
        String outcome;
        AuditServiceUtils.PatientStudyInfo patientStudyInfo;
        HashSet<String> accNos = new HashSet<>();
        HashSet<String> mppsUIDs = new HashSet<>();
        HashMap<String, List<String>> sopClassMap = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            patientStudyInfo = new AuditServiceUtils.PatientStudyInfo(reader.readLine());
            outcome = patientStudyInfo.getField(AuditServiceUtils.PatientStudyInfo.OUTCOME);
            if (null != patientStudyInfo.getField(AuditServiceUtils.PatientStudyInfo.ACCESSION_NO))
                accNos.add(patientStudyInfo.getField(AuditServiceUtils.PatientStudyInfo.ACCESSION_NO));
            String line;
            while ((line = reader.readLine()) != null) {
                AuditServiceUtils.InstanceInfo instanceInfo = new AuditServiceUtils.InstanceInfo(line);
                List<String> iuids = sopClassMap.get(instanceInfo.getField(AuditServiceUtils.InstanceInfo.CLASS_UID));
                if (iuids == null) {
                    iuids = new ArrayList<>();
                    sopClassMap.put(instanceInfo.getField(AuditServiceUtils.InstanceInfo.CLASS_UID), iuids);
                }
                iuids.add(instanceInfo.getField(AuditServiceUtils.InstanceInfo.INSTANCE_UID));
                mppsUIDs.add(instanceInfo.getField(AuditServiceUtils.InstanceInfo.MPPS_UID));
//                for (int i = AuditServiceUtils.InstanceInfo.ACCESSION_NO; instanceInfo.getField(i) != null; i++)
//                    accNos.add(instanceInfo.getField(i));
            }
        }
        accNos.remove("");
        mppsUIDs.remove("");
        Calendar eventTime = log().timeStamp();
        try {
            eventTime.setTimeInMillis(Files.getLastModifiedTime(path).toMillis());
        } catch (Exception e) {
            LOG.warn("Failed to get Last Modified Time of Audit Spool File - {} ", path, e);
        }
        auditInstancesStoredOrWADORetrieve(patientStudyInfo, accNos, mppsUIDs, sopClassMap, eventTime, eventType, outcome);
    }

    private void auditInstancesStoredOrWADORetrieve(AuditServiceUtils.PatientStudyInfo patientStudyInfo, HashSet<String> accNos,
                                                   HashSet<String> mppsUIDs, HashMap<String, List<String>> sopClassMap,
                                                   Calendar eventTime, AuditServiceUtils.EventType eventType, String outcomeDesc) {
        List<ActiveParticipant> apList = new ArrayList<>();
        List<ParticipantObjectIdentification> poiList = new ArrayList<>();
        apList.add(AuditMessages.createActiveParticipant(patientStudyInfo.getField(AuditServiceUtils.PatientStudyInfo.CALLING_AET),
                null, null, eventType.isSource, patientStudyInfo.getField(AuditServiceUtils.PatientStudyInfo.REMOTE_HOSTNAME),
                AuditMessages.NetworkAccessPointTypeCode.IPAddress, null, eventType.source));
        apList.add(AuditMessages.createActiveParticipant(
                patientStudyInfo.getField(AuditServiceUtils.PatientStudyInfo.CALLED_AET), AuditLogger.processID(),
                null, eventType.isDest, getLocalHostName(),
                AuditMessages.NetworkAccessPointTypeCode.IPAddress, null, eventType.destination));
        HashSet<Accession> acc = new HashSet<>();
        HashSet<MPPS> mpps = new HashSet<>();
        HashSet<SOPClass> sopC = new HashSet<>();
        for (String accNo : accNos)
            acc.add(AuditMessages.createAccession(accNo));
        for (String mppsUID : mppsUIDs)
            mpps.add(AuditMessages.createMPPS(mppsUID));
        for (Map.Entry<String, List<String>> entry : sopClassMap.entrySet())
            sopC.add(AuditMessages.createSOPClass(entry.getKey(), entry.getValue().size()));
        ParticipantObjectContainsStudy pocs = new ParticipantObjectContainsStudy();
        pocs.getStudyIDs().add(AuditMessages.createStudyIDs(patientStudyInfo.getField(AuditServiceUtils.PatientStudyInfo.STUDY_UID)));
        if (patientStudyInfo.getField(AuditServiceUtils.PatientStudyInfo.STUDY_DATE) != null)
            poiList.add(AuditMessages.createParticipantObjectIdentification(
                patientStudyInfo.getField(AuditServiceUtils.PatientStudyInfo.STUDY_UID),
                AuditMessages.ParticipantObjectIDTypeCode.StudyInstanceUID, null, null,
                AuditMessages.ParticipantObjectTypeCode.SystemObject, AuditMessages.ParticipantObjectTypeCodeRole.Report,
                null, null, null, acc, mpps, sopC, null, null, pocs, AuditMessages.createParticipantObjectDetail(
                            "StudyDate", patientStudyInfo.getField(AuditServiceUtils.PatientStudyInfo.STUDY_DATE).getBytes())));
        else
            poiList.add(AuditMessages.createParticipantObjectIdentification(
                patientStudyInfo.getField(AuditServiceUtils.PatientStudyInfo.STUDY_UID),
                AuditMessages.ParticipantObjectIDTypeCode.StudyInstanceUID, null, null,
                AuditMessages.ParticipantObjectTypeCode.SystemObject, AuditMessages.ParticipantObjectTypeCodeRole.Report,
                null, null, null, acc, mpps, sopC, null, null, pocs));
        poiList.add(AuditMessages.createParticipantObjectIdentification(
                StringUtils.maskEmpty(patientStudyInfo.getField(AuditServiceUtils.PatientStudyInfo.PATIENT_ID), noValue),
                AuditMessages.ParticipantObjectIDTypeCode.PatientNumber,
                StringUtils.maskEmpty(patientStudyInfo.getField(AuditServiceUtils.PatientStudyInfo.PATIENT_NAME), null), null,
                AuditMessages.ParticipantObjectTypeCode.Person, AuditMessages.ParticipantObjectTypeCodeRole.Patient,
                null, null, null, null, null, null, null, null, null));
        emitAuditMessage(log().timeStamp(), AuditMessages.createEventIdentification(
                AuditMessages.EventID.DICOMInstancesTransferred, eventType.eventActionCode, eventTime,
                eventType.outcomeIndicator, outcomeDesc), apList, poiList);
    }

    public void spoolRetrieve(RetrieveContext ctx, AuditServiceUtils.EventType et) {
        ArchiveDeviceExtension arcDev = device.getDeviceExtension(ArchiveDeviceExtension.class);
        boolean auditAggregate = arcDev.isAuditAggregate();
        Path dir = Paths.get(
                auditAggregate ? StringUtils.replaceSystemProperties(arcDev.getAuditSpoolDirectory()) : tmpdir);
        try {
            Path file = Files.createTempFile(dir, String.valueOf(et), null);
            boolean append = Files.exists(file);
            if (!append)
                Files.createDirectories(dir);
            try (LineWriter writer = new LineWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND))) {
                writer.writeLine(new AuditServiceUtils.RetrieveInfo(ctx));
                for (InstanceLocations il : ctx.getMatches()) {
                    Attributes attrs = il.getAttributes();
                    writer.writeLine(new AuditServiceUtils.RetrieveStudyInfo(attrs));
                }
            }
            if (!auditAggregate)
                aggregateAuditMessage(file);
        } catch (Exception e) {
            LOG.warn("Failed write to Audit Spool File - {} ", e);
        }
    }

    private void auditRetrieve(Path file, AuditServiceUtils.EventType eventType) throws IOException {
        Calendar eventTime = log().timeStamp();
        try {
            eventTime.setTimeInMillis(Files.getLastModifiedTime(file).toMillis());
        } catch (Exception e) {
            LOG.warn("Failed to get Last Modified Time of Audit Spool File - {} ", file, e);
        }
        List<ActiveParticipant> apList = new ArrayList<>();
        List<ParticipantObjectIdentification> poiList = new ArrayList<>();
        AuditServiceUtils.RetrieveInfo ri;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            ri = new AuditServiceUtils.RetrieveInfo(reader.readLine());
            apList.add(AuditMessages.createActiveParticipant(
                    ri.getField(AuditServiceUtils.RetrieveInfo.LOCALAET), AuditLogger.processID(),
                    null, eventType.isSource, getLocalHostName(),
                    AuditMessages.NetworkAccessPointTypeCode.IPAddress, null, eventType.source));
            apList.add(AuditMessages.createActiveParticipant(ri.getField(AuditServiceUtils.RetrieveInfo.DESTAET),
                    null, null, eventType.isDest,
                    ri.getField(AuditServiceUtils.RetrieveInfo.DESTNAPID), ri.getField(AuditServiceUtils.RetrieveInfo.DESTNAPCODE), null, eventType.destination));
            if (eventType.isOther)
                apList.add(AuditMessages.createActiveParticipant(ri.getField(AuditServiceUtils.RetrieveInfo.MOVEAET),
                        null, null, eventType.isOther,
                        ri.getField(AuditServiceUtils.RetrieveInfo.REQUESTORHOST), AuditMessages.NetworkAccessPointTypeCode.IPAddress, null));
            String line;
            HashMap<String, AuditServiceUtils.AccessionNumSopClassInfo> study_accNumSOPClassInfo = new HashMap<>();
            String pID = noValue;
            String pName = null;
            String studyDate = null;
            while ((line = reader.readLine()) != null) {
                AuditServiceUtils.RetrieveStudyInfo rInfo = new AuditServiceUtils.RetrieveStudyInfo(line);
                String studyInstanceUID = rInfo.getField(AuditServiceUtils.RetrieveStudyInfo.STUDYUID);
                AuditServiceUtils.AccessionNumSopClassInfo accessionNumSopClassInfo = study_accNumSOPClassInfo.get(studyInstanceUID);
                if (accessionNumSopClassInfo == null) {
                    accessionNumSopClassInfo = new AuditServiceUtils.AccessionNumSopClassInfo(rInfo.getField(AuditServiceUtils.RetrieveStudyInfo.ACCESSION));
                    study_accNumSOPClassInfo.put(studyInstanceUID, accessionNumSopClassInfo);
                }
                accessionNumSopClassInfo.addSOPInstance(rInfo);
                study_accNumSOPClassInfo.put(studyInstanceUID, accessionNumSopClassInfo);
                pID = rInfo.getField(AuditServiceUtils.RetrieveStudyInfo.PATIENTID);
                pName = rInfo.getField(AuditServiceUtils.RetrieveStudyInfo.PATIENTNAME);
                studyDate = rInfo.getField(AuditServiceUtils.RetrieveStudyInfo.STUDY_DATE);
            }
            HashSet<Accession> acc = new HashSet<>();
            HashSet<SOPClass> sopC = new HashSet<>();
            for (Map.Entry<String, AuditServiceUtils.AccessionNumSopClassInfo> entry : study_accNumSOPClassInfo.entrySet()) {
                if (null != entry.getValue().getAccNum())
                    acc.add(AuditMessages.createAccession(entry.getValue().getAccNum()));
                for (Map.Entry<String, HashSet<String>> sopClassMap : entry.getValue().getSopClassMap().entrySet())
                    sopC.add(AuditMessages.createSOPClass(sopClassMap.getKey(), sopClassMap.getValue().size()));
                ParticipantObjectContainsStudy pocs = new ParticipantObjectContainsStudy();
                pocs.getStudyIDs().add(AuditMessages.createStudyIDs(entry.getKey()));
                if (studyDate != null)
                    poiList.add(AuditMessages.createParticipantObjectIdentification(
                        entry.getKey(), AuditMessages.ParticipantObjectIDTypeCode.StudyInstanceUID, null, null,
                        AuditMessages.ParticipantObjectTypeCode.SystemObject,
                        AuditMessages.ParticipantObjectTypeCodeRole.Report, null, null, null, acc, null, sopC, null,
                            null, pocs, AuditMessages.createParticipantObjectDetail("StudyDate", studyDate.getBytes())));
                else
                    poiList.add(AuditMessages.createParticipantObjectIdentification(
                        entry.getKey(), AuditMessages.ParticipantObjectIDTypeCode.StudyInstanceUID, null, null,
                        AuditMessages.ParticipantObjectTypeCode.SystemObject,
                        AuditMessages.ParticipantObjectTypeCodeRole.Report, null, null, null, acc, null, sopC, null, null, pocs));
            }
            poiList.add(AuditMessages.createParticipantObjectIdentification(
                    pID, AuditMessages.ParticipantObjectIDTypeCode.PatientNumber, pName, null,
                    AuditMessages.ParticipantObjectTypeCode.Person,
                    AuditMessages.ParticipantObjectTypeCodeRole.Patient, null, null, null, null, null, null, null, null, null));
        }
        emitAuditMessage(log().timeStamp(), AuditMessages.createEventIdentification(eventType.eventID, eventType.eventActionCode,
                eventTime, eventType.outcomeIndicator, ri.getField(AuditServiceUtils.RetrieveInfo.OUTCOME)), apList, poiList);
    }
}

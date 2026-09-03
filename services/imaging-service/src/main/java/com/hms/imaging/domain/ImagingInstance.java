package com.hms.imaging.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * One image, recorded but not held.
 *
 * <p>{@code storageUri} points at the pixels and is null when no archive is configured — the state
 * a default deployment is in, and one the screens report rather than hide. This platform records
 * that an instance exists, what it is of and where somebody put it; a viewer or a PACS reads the
 * file.
 */
@Entity
@Table(name = "imaging_instances")
public class ImagingInstance extends BaseEntity {

    @Column(name = "sop_instance_uid", nullable = false, length = 64, updatable = false)
    private String sopInstanceUid;

    @Column(name = "series_id", nullable = false, updatable = false)
    private UUID seriesId;

    @Column(name = "sop_class_uid", length = 64)
    private String sopClassUid;

    @Column(name = "instance_number")
    private Integer instanceNumber;

    @Column(name = "rows_count")
    private Integer rowsCount;

    @Column(name = "columns_count")
    private Integer columnsCount;

    @Column(name = "transfer_syntax_uid", length = 64)
    private String transferSyntaxUid;

    @Column(name = "storage_uri", length = 500)
    private String storageUri;

    @Column(name = "byte_count")
    private Long byteCount;

    protected ImagingInstance() {
    }

    public ImagingInstance(String sopInstanceUid, UUID seriesId) {
        this.sopInstanceUid = sopInstanceUid;
        this.seriesId = seriesId;
    }

    public String getSopInstanceUid() {
        return sopInstanceUid;
    }

    public UUID getSeriesId() {
        return seriesId;
    }

    public String getSopClassUid() {
        return sopClassUid;
    }

    public void setSopClassUid(String sopClassUid) {
        this.sopClassUid = sopClassUid;
    }

    public Integer getInstanceNumber() {
        return instanceNumber;
    }

    public void setInstanceNumber(Integer instanceNumber) {
        this.instanceNumber = instanceNumber;
    }

    public Integer getRowsCount() {
        return rowsCount;
    }

    public void setRowsCount(Integer rowsCount) {
        this.rowsCount = rowsCount;
    }

    public Integer getColumnsCount() {
        return columnsCount;
    }

    public void setColumnsCount(Integer columnsCount) {
        this.columnsCount = columnsCount;
    }

    public String getTransferSyntaxUid() {
        return transferSyntaxUid;
    }

    public void setTransferSyntaxUid(String transferSyntaxUid) {
        this.transferSyntaxUid = transferSyntaxUid;
    }

    public String getStorageUri() {
        return storageUri;
    }

    public void setStorageUri(String storageUri) {
        this.storageUri = storageUri;
    }

    public Long getByteCount() {
        return byteCount;
    }

    public void setByteCount(Long byteCount) {
        this.byteCount = byteCount;
    }
}

package com.uav.dispatch.service;

import com.uav.attachment.service.AttachmentObjectStorage;
import com.uav.dispatch.mapper.DispatchMaterialRepository;
import com.uav.dispatch.mapper.DispatchOrderRepository;
import com.uav.dispatch.pojo.dto.DispatchMaterialUploadDTO;
import com.uav.dispatch.pojo.entity.DispatchMaterial;
import com.uav.dispatch.pojo.entity.DispatchOrder;
import com.uav.dispatch.pojo.vo.DispatchMaterialVO;
import com.uav.dispatch.pojo.vo.DispatchUploadTicketVO;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

/** Real object upload and server-side existence/size verification. Tickets are never evidence. */
@Service
public class DispatchMaterialService {
    private static final Set<String> IMAGES = Set.of("image/jpeg", "image/png", "image/webp");
    private final DispatchOrderRepository orders;
    private final DispatchMaterialRepository materials;
    private final AttachmentObjectStorage storage;
    public DispatchMaterialService(DispatchOrderRepository orders, DispatchMaterialRepository materials, AttachmentObjectStorage storage) {
        this.orders = orders; this.materials = materials; this.storage = storage;
    }
    @Transactional
    public DispatchUploadTicketVO ticket(Long caller, String orderId, DispatchMaterialUploadDTO input) {
        configured();
        var order = orders.lockOrder(orderId).orElseThrow(this::notFound);
        requireUpload(order, caller, input.purpose());
        boolean image = IMAGES.contains(input.contentType());
        boolean video = Set.of("video/mp4", "video/quicktime").contains(input.contentType());
        long limit = image ? 20L * 1024 * 1024 : 500L * 1024 * 1024;
        if ((!image && !video) || input.sizeBytes() <= 0 || input.sizeBytes() > limit) throw invalid();
        var material = new DispatchMaterial();
        material.setId(UUID.randomUUID().toString()); material.setOrderId(orderId); material.setUploaderId(caller);
        material.setAssignmentVersion(order.getAssignmentVersion()); material.setPurpose(input.purpose());
        material.setObjectKey("dispatch-materials/" + orderId + "/" + material.getId());
        material.setFileName(input.fileName().trim()); material.setContentType(input.contentType());
        material.setSizeBytes(input.sizeBytes()); material.setCreatedAt(LocalDateTime.now());
        String url = storage.presignedPut(material.getObjectKey(), AttachmentObjectStorage.PRESIGN_EXPIRY_SECONDS, input.contentType());
        materials.save(material);
        return new DispatchUploadTicketVO(material.getId(), url, "PUT", input.contentType(), input.sizeBytes(), AttachmentObjectStorage.PRESIGN_EXPIRY_SECONDS);
    }
    @Transactional
    public DispatchMaterialVO confirm(Long caller, String orderId, String id) {
        configured();
        var order = orders.lockOrder(orderId).orElseThrow(this::notFound);
        var material = materials.findById(id).filter(m -> orderId.equals(m.getOrderId()) && caller.equals(m.getUploaderId())).orElseThrow(this::notFound);
        requireUpload(order, caller, material.getPurpose());
        if (material.getPurpose().equals("PILOT_DELIVERY") && material.getAssignmentVersion() != order.getAssignmentVersion()) throw invalid();
        if (!storage.isValidObject(material.getObjectKey(), material.getSizeBytes())) throw invalid();
        material.setUploaded(true); material.setConfirmedAt(LocalDateTime.now()); materials.save(material);
        return view(material);
    }
    @Transactional(readOnly = true)
    public List<DispatchMaterialVO> list(Long caller, String orderId) {
        configured();
        var order = orders.findById(orderId).orElseThrow(this::notFound);
        if (!caller.equals(order.getUserId()) && !caller.equals(order.getAssignedPilotId())) throw notFound();
        return materials.findByOrderIdOrderByCreatedAtAsc(orderId).stream().filter(this::verified).map(this::view).toList();
    }
    public void requireVerified(DispatchOrder order, Long uploader, String purpose, List<String> ids) {
        configured();
        if (ids == null || ids.isEmpty() || ids.size() > 20 || new HashSet<>(ids).size() != ids.size()) throw invalid();
        for (String id : ids) {
            var m = materials.findById(id).orElseThrow(this::invalid);
            if (!order.getId().equals(m.getOrderId()) || !uploader.equals(m.getUploaderId()) || !purpose.equals(m.getPurpose())
                    || (purpose.equals("PILOT_DELIVERY") && m.getAssignmentVersion() != order.getAssignmentVersion()) || !verified(m)) throw invalid();
        }
    }
    private boolean verified(DispatchMaterial m) { return m.isUploaded() && storage.isValidObject(m.getObjectKey(), m.getSizeBytes()); }
    private DispatchMaterialVO view(DispatchMaterial m) {
        return new DispatchMaterialVO(m.getId(), m.getPurpose(), m.getFileName(), m.getContentType(), m.getSizeBytes(),
                storage.presignedGet(m.getObjectKey(), AttachmentObjectStorage.PRESIGN_EXPIRY_SECONDS), "UPLOADED", m.getUploaderId(), m.getConfirmedAt());
    }
    private void requireUpload(DispatchOrder order, Long caller, String purpose) {
        boolean owner = caller.equals(order.getUserId());
        if (purpose.equals("PILOT_DELIVERY")) {
            if (!caller.equals(order.getAssignedPilotId()) || !order.getStage().equals("IN_TRANSIT")) throw invalid();
        } else if (purpose.equals("SHIPPER_RECEIPT")) {
            if (!owner || !order.getStage().equals("DELIVERY_CONFIRMATION")) throw invalid();
        } else if (!Set.of("CARGO", "START_SITE", "END_SITE").contains(purpose) || !owner
                || !Set.of("AWAITING_DEPOSIT", "WAITING_ACCEPTANCE", "PILOT_PREPARING", "PILOT_ARRIVED").contains(order.getStage())) throw invalid();
    }
    private void configured() {
        if (!storage.isConfigured()) throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCode.DISPATCH_UNAVAILABLE, "对象存储未配置，不能上传或核验材料");
    }
    private BusinessException notFound() { return new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND); }
    private BusinessException invalid() {
        return new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.DELIVERY_EVIDENCE_REQUIRED, "材料尚未上传核验、用途或归属不符，或当前阶段不允许此操作");
    }
}

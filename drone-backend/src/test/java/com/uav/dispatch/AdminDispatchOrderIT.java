package com.uav.dispatch;

import com.uav.dispatch.mapper.DispatchAssignmentRepository;
import com.uav.dispatch.mapper.DispatchOrderRepository;
import com.uav.dispatch.pojo.entity.DispatchAssignment;
import com.uav.dispatch.pojo.entity.DispatchOrder;
import com.uav.flight.mapper.FlightConnectionRepository;
import com.uav.flight.pojo.entity.FlightConnection;
import com.uav.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 管理端派单订单列表/详情：仅管理员；过滤、倒序、详情时间线与连机记录。 */
class AdminDispatchOrderIT extends IntegrationTestBase {
    @Autowired DispatchOrderRepository orders;
    @Autowired DispatchAssignmentRepository assignments;
    @Autowired FlightConnectionRepository connections;
    @Autowired com.uav.dispatch.mapper.WaybillAssessmentRepository assessmentRepo;

    private DispatchOrder order(Long owner, String stage, String funds, LocalDateTime created, Long pilot) {
        DispatchOrder o = new DispatchOrder();
        o.setId(UUID.randomUUID().toString());
        o.setUserId(owner);
        o.setRequestKey(UUID.randomUUID().toString());
        var assessment = new com.uav.dispatch.pojo.entity.WaybillAssessment();
        assessment.setId(UUID.randomUUID().toString());
        assessment.setUserId(owner);
        assessment.setFingerprint("fp");
        assessment.setFieldsJson("{}");
        assessment.setEvaluationJson("{}");
        assessment.setExpiresAt(created.plusDays(1));
        assessmentRepo.saveAndFlush(assessment);
        o.setAssessmentId(assessment.getId());
        o.setFingerprint("fp");
        o.setFieldsJson("{\"cargoName\":\"medicine\"}");
        o.setDepositAmount(new BigDecimal("50.00"));
        o.setPilotServiceFee(new BigDecimal("30.00"));
        o.setStage(stage);
        o.setFundsStatus(funds);
        o.setCreatedAt(created);
        o.setUpdatedAt(created);
        o.setAssignedPilotId(pilot);
        o.setAssignmentVersion(pilot == null ? 0 : 1);
        return orders.saveAndFlush(o);
    }

    @Test void adminListsFiltersAndSortsNewestFirst() throws Exception {
        var owner = accounts().registerUser();
        var pilot = accounts().registerRider();
        LocalDateTime base = LocalDateTime.of(2031, 1, 1, 8, 0);
        DispatchOrder older = order(owner.id(), "WAITING_ACCEPTANCE", "PAID", base, null);
        DispatchOrder newer = order(owner.id(), "IN_FLIGHT", "PAID", base.plusHours(1), pilot.id());
        order(owner.id(), "AWAITING_DEPOSIT", "UNPAID", base.plusHours(2), null);
        var admin = accounts().adminLogin();

        mockMvc.perform(get("/admin/dispatch/orders").header("Authorization", admin.authorization())
                        .param("keyword", owner.userName()).param("fundsStatus", "PAID"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].orderId").value(newer.getId()))
                .andExpect(jsonPath("$.data.content[0].pilot.userName").value(pilot.userName()))
                .andExpect(jsonPath("$.data.content[0].shipper.userName").value(owner.userName()))
                .andExpect(jsonPath("$.data.content[1].orderId").value(older.getId()));

        mockMvc.perform(get("/admin/dispatch/orders").header("Authorization", admin.authorization())
                        .param("keyword", pilot.userName()).param("stage", "IN_FLIGHT")
                        .param("createdFrom", "2031-01-01T08:30:00").param("createdTo", "2031-01-01T10:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].orderId").value(newer.getId()));

        mockMvc.perform(get("/admin/dispatch/orders").header("Authorization", admin.authorization())
                        .param("keyword", older.getId().substring(0, 8)).param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100))
                .andExpect(jsonPath("$.data.content[0].orderId").value(older.getId()));

        mockMvc.perform(get("/admin/dispatch/orders").header("Authorization", admin.authorization())
                        .param("createdFrom", "2031-01-02T00:00:00").param("createdTo", "2031-01-01T00:00:00"))
                .andExpect(status().isBadRequest());
    }

    @Test void adminDetailHasTimelineAssignmentsAndConnections() throws Exception {
        var owner = accounts().registerUser();
        var pilot = accounts().registerRider();
        LocalDateTime base = LocalDateTime.of(2031, 2, 1, 8, 0);
        DispatchOrder o = order(owner.id(), "IN_FLIGHT", "PAID", base, pilot.id());
        o.setStartedAt(base.plusMinutes(30));
        o.setExecutedSerialNumber("SN-ADMIN-1");
        o.setExecutedModelCode("M350RTK");
        o.setExecutedModelName("Matrice 350 RTK");
        orders.saveAndFlush(o);
        DispatchAssignment a = new DispatchAssignment();
        a.setOrderId(o.getId());
        a.setAssignmentVersion(1);
        a.setPilotId(pilot.id());
        a.setOfferRevision(1);
        a.setAcceptedAt(base.plusMinutes(10));
        a.setArrivedAt(base.plusMinutes(20));
        assignments.saveAndFlush(a);
        FlightConnection c = new FlightConnection();
        c.setId(UUID.randomUUID().toString());
        c.setOrderId(o.getId());
        c.setPilotId(pilot.id());
        c.setAssignmentVersion(1);
        c.setRequestKey(UUID.randomUUID().toString());
        c.setSerialNumber("SN-ADMIN-1");
        c.setModelCode("M350RTK");
        c.setModelName("Matrice 350 RTK");
        c.setOpenedAt(base.plusMinutes(25));
        connections.saveAndFlush(c);
        var admin = accounts().adminLogin();

        mockMvc.perform(get("/admin/dispatch/orders/" + o.getId()).header("Authorization", admin.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order.orderId").value(o.getId()))
                .andExpect(jsonPath("$.data.order.fields.cargoName").value("medicine"))
                .andExpect(jsonPath("$.data.order.executedDrone.serialNumber").value("SN-ADMIN-1"))
                .andExpect(jsonPath("$.data.shipper.userName").value(owner.userName()))
                .andExpect(jsonPath("$.data.pilot.userId").value(pilot.id()))
                .andExpect(jsonPath("$.data.timeline[0].event").value("CREATED"))
                .andExpect(jsonPath("$.data.timeline[1].event").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.timeline[2].event").value("ARRIVED"))
                .andExpect(jsonPath("$.data.timeline[3].event").value("STARTED"))
                .andExpect(jsonPath("$.data.assignments[0].pilot.userName").value(pilot.userName()))
                .andExpect(jsonPath("$.data.connections[0].modelCode").value("M350RTK"));

        mockMvc.perform(get("/admin/dispatch/orders/" + UUID.randomUUID()).header("Authorization", admin.authorization()))
                .andExpect(status().isNotFound());
    }

    @Test void nonAdminsAreForbidden() throws Exception {
        var user = accounts().registerUser();
        var pilot = accounts().registerRider();
        for (var acc : new com.uav.support.TestAccounts.Account[]{user, pilot}) {
            mockMvc.perform(get("/admin/dispatch/orders").header("Authorization", acc.authorization()))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/admin/dispatch/orders/x").header("Authorization", acc.authorization()))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/admin/dispatch/orders")).andExpect(status().isUnauthorized());
    }
}

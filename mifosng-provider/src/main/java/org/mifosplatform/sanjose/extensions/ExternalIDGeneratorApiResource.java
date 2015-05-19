/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.mifosplatform.sanjose.extensions;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.mifosplatform.infrastructure.security.service.PlatformSecurityContext;
import org.mifosplatform.organisation.office.domain.Office;
import org.mifosplatform.organisation.office.domain.OfficeRepository;
import org.mifosplatform.portfolio.client.domain.Client;
import org.mifosplatform.portfolio.client.domain.ClientRepository;
import org.mifosplatform.portfolio.group.data.CenterData;
import org.mifosplatform.portfolio.group.domain.Group;
import org.mifosplatform.portfolio.group.domain.GroupRepository;
import org.mifosplatform.portfolio.group.service.CenterReadPlatformService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.google.gson.JsonParser;
import com.google.gson.JsonObject;

@Path("/extensions/extidgen")
@Component
@Scope("singleton")
public class ExternalIDGeneratorApiResource {

    private final static Logger logger = LoggerFactory.getLogger(ExternalIDGeneratorApiResource.class);
    private final PlatformSecurityContext securityContext;
    private final OfficeRepository officeRepository;
    private final CenterReadPlatformService centerReadPlatformService;
    private final GroupRepository groupRepository;
    private final ClientRepository clientRepository;
    private final Pattern rInt;

    @Autowired
    public ExternalIDGeneratorApiResource(final PlatformSecurityContext securityContext,
                                          final OfficeRepository officeRepository,
                                          final CenterReadPlatformService centerReadPlatformService,
                                          final GroupRepository groupRepository,
                                          final ClientRepository clientRepository) {
        super();
        this.securityContext = securityContext;
        this.officeRepository = officeRepository;
        this.centerReadPlatformService = centerReadPlatformService;
        this.groupRepository = groupRepository;
        this.clientRepository = clientRepository;
        this.rInt = Pattern.compile("^\\d+$");
    }

    @POST
    @Transactional
    public Response genererateExternalId(@Context final UriInfo uriInfo,
            @HeaderParam("X-Mifos-Entity") final String entity,
            @HeaderParam("X-Mifos-Action") final String action, final String payload) {

        Pattern r = Pattern.compile("^(create|update)$", Pattern.CASE_INSENSITIVE);
        Matcher m = r.matcher(action);

        if (m.find()) {
            JsonObject data = new JsonParser().parse(payload).getAsJsonObject();
            final Long resourceId = data.get("resourceId").getAsLong();
            if (entity.equalsIgnoreCase("office")) {
                this.generateExtIdForOffice(resourceId);
            } else if (entity.equalsIgnoreCase("center")) {
                this.generateExtIdForCenter(resourceId);
            } else if (entity.equalsIgnoreCase("group")) {
                this.generateExtIdForGroup(resourceId);
            } else if (entity.equalsIgnoreCase("client")) {
                this.generateExtIdForClient(resourceId);
            }
        }

        return Response.ok().build();
    }

    private String generateExtIdForOffice(final Long officeId) {
        final Office office = this.officeRepository.findOne(officeId);
        if (office.getExternalId() != null) {
            logger.debug("OFFICE: ExternalId already set");
            return office.getExternalId();
        }
        final Office parentOffice = office.getParent();
        boolean isTaluk = false;
        if (parentOffice == null) {
            logger.debug("OFFICE: Parent office missing");
            return "";
        }
        String externalId = "";
        if (1 != parentOffice.getId()) {
            isTaluk = true;
            externalId = parentOffice.getExternalId();
            if (externalId == null) {
                externalId = this.generateExtIdForOffice(parentOffice.getId());
            }
        }
        final List<Office> parentsChildren = parentOffice.getChildren();
        if (parentsChildren == null || parentsChildren.isEmpty()) {
            logger.debug("OFFICE: Parent office has no children");
            return "";
        }
        String maxId = externalId + (isTaluk ? "000" : "00");
        for (final Office child : parentsChildren) {
            String childExternalId = child.getExternalId();
            if (childExternalId != null && rInt.matcher(childExternalId).find()
                    && childExternalId.compareTo(maxId) > 0) {
                maxId = childExternalId;
            }
        }
        if (isTaluk) {
            final int nextId = 1 + Integer.parseInt(maxId.substring(maxId.length() - 3));
            externalId += String.format("%03d", nextId);
        } else {
            final int nextId = 1 + Integer.parseInt(maxId);
            externalId += String.format("%02d", nextId);
        }
        office.setExternalId(externalId);
        this.officeRepository.save(office);
        return externalId;
    }

    private String generateExtIdForCenter(final Long centerId) {
        final Group center = this.groupRepository.findOne(centerId);
        if (center.getExternalId() != null) {
            logger.debug("CENTER: ExternalId already set");
            return "";
        }
        if (!center.isCenter()) {
            logger.debug("CENTER: is not a center");
            return "";
        }
        Long officeId = center.officeId();
        Office parent = center.getOffice();
        if (parent == null) {
            logger.debug("CENTER: doesn't have a parent office");
            return "";
        }
        String externalId = parent.getExternalId();
        if (externalId.isEmpty()) {
            externalId = this.generateExtIdForOffice(parent.getId());
        }
        Collection<CenterData> centers = this.centerReadPlatformService.retrieveAllForOffice(officeId);
        String maxId = externalId + "00";
        for(final CenterData childCenter: centers) {
            String childExternalId = childCenter.getExternalId();
            if (childExternalId != null && rInt.matcher(childExternalId).find()
                    && childExternalId.compareTo(maxId) > 0) {
                maxId = childExternalId;
            }
        }
        int nextId = 1 + Integer.parseInt(maxId.substring(maxId.length() - 2));
        externalId += String.format("%02d", nextId);
        logger.debug("CENTER maxId: " + maxId + ", New ExternalId: " + externalId);
        center.setExternalId(externalId);
        this.groupRepository.save(center);
        return externalId;
    }

    private String generateExtIdForGroup(final Long groupId) {
        final Group group = this.groupRepository.findOne(groupId);
        if (group.getExternalId() != null) {
            logger.debug("GROUP: ExternalId already set");
            return group.getExternalId();
        }
        if (!group.isGroup()) {
            logger.debug("GROUP: Is not a group");
            return "";
        }
        Group parent = group.getParent();
        if (parent == null) {
            logger.debug("GROUP: Doesn't have parent");
            return "";
        }
        Long parentId = parent.getId();
        String externalId = parent.getExternalId();
        if (externalId == null) {
            if (!parent.isCenter()) {
                logger.debug("GROUP: Parent is not center");
                return "";
            }
            externalId = this.generateExtIdForCenter(parent.getId());
        }
        String maxId = externalId + "00";
        Collection<Group> childGroups = this.groupRepository.findByParentId(parentId);
        for (final Group child : childGroups) {
            String childExternalId = child.getExternalId();
            if (childExternalId != null && rInt.matcher(childExternalId).find()
                    && childExternalId.compareTo(maxId) > 0) {
                maxId = childExternalId;
            }
        }
        int nextId = 1 + Integer.parseInt(maxId.substring(maxId.length() - 2));
        externalId += String.format("%02d", nextId);
        logger.debug("GROUP maxId: " + maxId + ", New ExternalId: " + externalId);
        group.setExternalId(externalId);
        this.groupRepository.save(group);
        return externalId;
    }

    private String generateExtIdForClient(final Long clientId) {
        Client client = this.clientRepository.findOne(clientId);
        if (client.getExternalId() != null) {
            logger.debug("CLIENT: ExternalId already set");
            return "";
        }
        Set<Group> groups = client.getGroups();
        if (groups.size() == 0) {
            logger.debug("Client: Doesn't belong to a group");
            return "";
        } else if (groups.size() > 1) {
            logger.debug("Client: Belongs to multiple groups");
            return "";
        }
        String externalId = "";
        for(Group group : groups) {
            externalId = group.getExternalId();
            if (externalId == null) {
                externalId = this.generateExtIdForGroup(group.getId());
            }
            Set<Client> clients = group.getClientMembers();
            String maxId = externalId + "0000";
            for (final Client child : clients) {
                String childExternalId = child.getExternalId();
                if (childExternalId != null && rInt.matcher(childExternalId).find()
                        && childExternalId.compareTo(maxId) > 0) {
                    maxId = childExternalId;
                }
            }
            int nextId = 1 + Integer.parseInt(maxId.substring(maxId.length() - 4));
            externalId += String.format("%04d", nextId);
            logger.debug("CLIENT: maxId: " + maxId + "externalId: " + externalId);
            client.setExternalId(externalId);
            this.clientRepository.save(client);
        }
        return externalId;
    }
 }

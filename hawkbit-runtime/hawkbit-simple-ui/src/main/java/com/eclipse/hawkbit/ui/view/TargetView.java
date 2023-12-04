/**
 * Copyright (c) 2023 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package com.eclipse.hawkbit.ui.view;

import com.eclipse.hawkbit.ui.HawkbitClient;
import com.eclipse.hawkbit.ui.view.util.Filter;
import com.eclipse.hawkbit.ui.MainLayout;
import com.eclipse.hawkbit.ui.view.util.SelectionGrid;
import com.eclipse.hawkbit.ui.view.util.TableView;
import com.eclipse.hawkbit.ui.view.util.Utils;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.CheckboxGroup;
import com.vaadin.flow.component.dependency.Uses;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import org.eclipse.hawkbit.mgmt.json.model.tag.MgmtTag;
import org.eclipse.hawkbit.mgmt.json.model.target.MgmtTarget;
import org.eclipse.hawkbit.mgmt.json.model.target.MgmtTargetRequestBody;
import org.eclipse.hawkbit.mgmt.json.model.targetfilter.MgmtTargetFilterQuery;
import org.eclipse.hawkbit.mgmt.json.model.targetfilter.MgmtTargetFilterQueryRequestBody;
import org.eclipse.hawkbit.mgmt.json.model.targettype.MgmtTargetType;
import org.eclipse.hawkbit.mgmt.rest.api.MgmtTargetFilterQueryRestApi;
import org.eclipse.hawkbit.mgmt.rest.api.MgmtTargetRestApi;
import org.eclipse.hawkbit.mgmt.rest.api.MgmtTargetTagRestApi;
import org.eclipse.hawkbit.mgmt.rest.api.MgmtTargetTypeRestApi;
import org.springframework.util.ObjectUtils;

import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

@PageTitle("Targets")
@Route(value = "targets", layout = MainLayout.class)
@RolesAllowed({"TARGET_READ"})
@Uses(Icon.class)
public class TargetView extends TableView<MgmtTarget, String> {

    private final HawkbitClient hawkbitClient;

    private final MgmtTargetRestApi targetRestApi;
    private final MgmtTargetTypeRestApi targetTypeRestApi;
    private final MgmtTargetTagRestApi targetTagRestApi;
    private final MgmtTargetFilterQueryRestApi targetFilterQueryRestApi;

    public TargetView(final HawkbitClient hawkbitClient) {
        super(
                new RawFilter(hawkbitClient), new SimpleFilter(hawkbitClient),
                new SelectionGrid.EntityRepresentation<>(MgmtTarget.class, MgmtTarget::getControllerId) {

                    @Override
                    protected void addColumns(final Grid<MgmtTarget> grid) {
                        grid.addColumn(MgmtTarget::getControllerId).setHeader("Controller Id").setAutoWidth(true);
                        grid.addColumn(MgmtTarget::getName).setHeader("Name").setAutoWidth(true);
                        grid.addColumn(MgmtTarget::getTargetTypeName).setHeader("Type").setAutoWidth(true);

                        grid.setItemDetailsRenderer(new ComponentRenderer<>(
                                TargetDetails::new, TargetDetails::setItem));
                    }
                },
                (query, filter) -> hawkbitClient.getTargetRestApi()
                        .getTargets(
                                query.getOffset(), query.getPageSize(), "name:asc",
                                filter)
                        .getBody()
                        .getContent()
                        .stream(),
                source -> new RegisterDialog(hawkbitClient).result(),
                selectionGrid -> {
                    selectionGrid.getSelectedItems().forEach(toDelete ->
                            hawkbitClient.getTargetRestApi().deleteTarget(toDelete.getControllerId()));
                    return CompletableFuture.completedFuture(null);
                });
        this.hawkbitClient = hawkbitClient;
        this.targetRestApi = hawkbitClient.getTargetRestApi();
        this.targetTypeRestApi = hawkbitClient.getTargetTypeRestApi();
        this.targetTagRestApi = hawkbitClient.getTargetTagRestApi();
        this.targetFilterQueryRestApi = hawkbitClient.getTargetFilterQueryRestApi();
    }

    private static class SimpleFilter implements Filter.Rsql {

        private final HawkbitClient hawkbitClient;

        private final TextField controllerId;
        private final CheckboxGroup<MgmtTargetType> type;
        private final CheckboxGroup<MgmtTag> tag;

        private SimpleFilter(final HawkbitClient hawkbitClient) {
            this.hawkbitClient = hawkbitClient;

            controllerId = Utils.textField("Controller Id");
            controllerId.setPlaceholder("<controller id filter>");
            type = new CheckboxGroup<>("Type");
            type.setItemLabelGenerator(MgmtTargetType::getName);
            tag = new CheckboxGroup<>("Tag");
            tag.setItemLabelGenerator(MgmtTag::getName);
        }

        @Override
        public List<Component> components() {
            final List<Component> components = new LinkedList<>();
            components.add(controllerId);
            type.setItems(hawkbitClient.getTargetTypeRestApi().getTargetTypes(0, 20, "name:asc", null).getBody().getContent());
            if (!type.getValue().isEmpty()) {
                components.add(type);
            }
            tag.setItems(hawkbitClient.getTargetTagRestApi().getTargetTags(0, 20, "name:asc", null).getBody().getContent());
            if (!tag.isEmpty()) {
                components.add(tag);
            }
            return components;
        }

        @Override
        public String filter() {
            return Filter.filter(
                    Map.of(
                            "controllerid", controllerId.getOptionalValue(),
                            "targettype.name", type.getSelectedItems().stream().map(MgmtTargetType::getName)
                                    .toList(),
                            "tag", tag.getSelectedItems()));
        }
    }

    private static class RawFilter implements Filter.Rsql {

        private final HawkbitClient hawkbitClient;

        private final TextField textFilter = new TextField("Raw Filter");
        private final VerticalLayout layout = new VerticalLayout();

        private RawFilter(final HawkbitClient hawkbitClient) {
            this.hawkbitClient = hawkbitClient;

            textFilter.setPlaceholder("<raw filter>");
            final Select<MgmtTargetFilterQuery> savedFilters = new Select<>(
                    "Saved Filters",
                    e -> {
                        if (e.getValue() != null) {
                            textFilter.setValue(e.getValue().getQuery());
                        }
                    });
            savedFilters.setEmptySelectionAllowed(true);
            savedFilters.setItems(
                    Optional.ofNullable(
                                    hawkbitClient.getTargetFilterQueryRestApi()
                                            .getFilters(0, 30, null, null, null)
                                            .getBody().getContent())
                            .orElse(Collections.emptyList()));
            savedFilters.setItemLabelGenerator(query -> Optional.ofNullable(query).map(MgmtTargetFilterQuery::getName).orElse("<select saved filter>"));
            savedFilters.setWidthFull();

            textFilter.setWidthFull();
            final Button saveBtn = Utils.tooltip(new Button(VaadinIcon.ARCHIVE.create()), "Save (Enter)");
            saveBtn.addClickListener(e -> {
                new Utils.BaseDialog("Save Filter") {{
                    setHeight("40%");
                    final Button finishBtn = Utils.tooltip(new Button("Save"), "Save (Enter)");
                    final TextField name = Utils.textField(
                            "Name",
                            e -> finishBtn.setEnabled(!e.getHasValue().isEmpty()));
                    name.focus();
                    finishBtn.addClickShortcut(Key.ENTER);
                    finishBtn.setEnabled(false);
                    finishBtn.addClickListener(e -> {
                        final MgmtTargetFilterQueryRequestBody createRequest = new MgmtTargetFilterQueryRequestBody();
                        createRequest.setName(name.getValue());
                        createRequest.setQuery(textFilter.getValue());
                        hawkbitClient.getTargetFilterQueryRestApi().createFilter(createRequest);
                        savedFilters.setItems(
                                hawkbitClient.getTargetFilterQueryRestApi()
                                        .getFilters(0, 30, null, null, null).getBody().getContent());
                        close();
                    });
                    add(name, finishBtn);
                    open();
                }};
            });
            saveBtn.addClickShortcut(Key.ENTER);

            layout.setSpacing(false);
            final HorizontalLayout textSaveLayout = new HorizontalLayout(textFilter, saveBtn);
            textSaveLayout.setAlignItems(FlexComponent.Alignment.BASELINE);
            textSaveLayout.setWidthFull();
            layout.add(savedFilters, textSaveLayout);
        }

        @Override
        public List<Component> components() {
            return List.of(layout);
        }

        @Override
        public String filter() {
            return textFilter.getOptionalValue().orElse(null);
        }
    }

    private static class TargetDetails extends FormLayout {

        private final TextArea description = new TextArea("Description");
        private final TextField createdBy = Utils.textField("Created by");
        private final TextField createdAt = Utils.textField("Created at");
        private final TextField lastModifiedBy = Utils.textField("Last modified by");
        private final TextField lastModifiedAt = Utils.textField("Last modified at");

        private TargetDetails() {
            description.setMinLength(2);
            Stream.of(
                    description,
                    createdBy, createdAt,
                    lastModifiedBy, lastModifiedAt)
                    .forEach(field -> {
                        field.setReadOnly(true);
                        add(field);
                    });

            setResponsiveSteps(new ResponsiveStep("0", 2));
            setColspan(description, 2);
        }

        private void setItem(final MgmtTarget target) {
            description.setValue(target.getDescription());
            createdBy.setValue(target.getCreatedBy());
            createdAt.setValue(new Date(target.getCreatedAt()).toString());
            lastModifiedBy.setValue(target.getLastModifiedBy());
            lastModifiedAt.setValue(new Date(target.getLastModifiedAt()).toString());
        }
    }

    private static class RegisterDialog extends Utils.BaseDialog {

        private final Select<MgmtTargetType> type;
        private final TextField controllerId;
        private final TextField name;
        private final TextArea description;

        private RegisterDialog(final HawkbitClient hawkbitClient) {
            super("Register Target");

            final Button register = Utils.tooltip(new Button("Register"), "Register (Enter)");
            type = new Select<>(
                    "Type",
                    e -> {},
                    hawkbitClient.getTargetTypeRestApi()
                            .getTargetTypes(0, 30, "name:asc", null)
                            .getBody()
                            .getContent()
                            .toArray(new MgmtTargetType[0]));
            type.setWidthFull();
            type.setEmptySelectionAllowed(true);
            type.setItemLabelGenerator(item -> item == null ? "" : item.getName());
            controllerId = Utils.textField(
                    "Controller Id",
                    e -> register.setEnabled(!e.getHasValue().isEmpty()));
            controllerId.focus();
            name = Utils.textField("Name");
            name.setWidthFull();
            description = new TextArea("Description");
            description.setMinLength(2);
            description.setWidthFull();

            addCreateClickListener(register, hawkbitClient);
            register.setEnabled(false);
            register.addClickShortcut(Key.ENTER);
            final Button cancel = Utils.tooltip(new Button("Cancel"), "Cancel (Esc)");
            cancel.addClickListener(e -> close());
            register.addClickShortcut(Key.ESCAPE);
            final HorizontalLayout actions = new HorizontalLayout(register, cancel);
            actions.setSizeFull();
            actions.setPadding(true);
            actions.setSpacing(true);

            final VerticalLayout layout = new VerticalLayout();
            layout.setSizeFull();
            layout.setPadding(true);
            layout.setSpacing(false);
            layout.add(type, controllerId, name, description, actions);
            add(layout);
            open();
        }

        private void addCreateClickListener(final Button register, final HawkbitClient hawkbitClient) {
            register.addClickListener(e -> {
                final MgmtTargetRequestBody request = new MgmtTargetRequestBody()
                        .setControllerId(controllerId.getValue())
                        .setName(name.getValue())
                        .setDescription(description.getValue());
                if (!ObjectUtils.isEmpty(type.getValue())) {
                    request.setTargetType(type.getValue().getTypeId());
                }
                hawkbitClient.getTargetRestApi().createTargets(
                        List.of(request))
                        .getBody()
                        .stream()
                        .findFirst()
                        .orElseThrow()
                        .getControllerId();
                close();
            });
        }
    }
}
package com.janboerman.invsee.folia;

import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class PluginMetadataTest {
    private PluginDescriptionFile descriptor() throws Exception {
        return new PluginDescriptionFile(getClass().getClassLoader().getResourceAsStream("plugin.yml"));
    }
    private Map<String, Permission> permissions() throws Exception {
        return descriptor().getPermissions().stream().collect(Collectors.toMap(Permission::getName, p -> p));
    }
    @Test void originalAggregatePermissionsHaveExplicitChildrenAndSafeDefaults() throws Exception {
        var permissions = permissions();
        for (String kind : List.of("view", "edit", "exempt", "bypass-exempt")) {
            var group = permissions.get("invseeplusplus." + kind);
            assertNotNull(group);
            assertEquals(kind.equals("view") || kind.equals("edit") ? PermissionDefault.OP : PermissionDefault.FALSE, group.getDefault());
            for (String command : List.of("invsee", "endersee")) {
                String suffix = kind.equals("view") || kind.equals("edit") ? command + "." + kind : kind + "." + command;
                assertEquals(Boolean.TRUE, group.getChildren().get("invseeplusplus." + suffix));
            }
        }
    }
    @Test void bukkitDescriptorRegistersLiteralAggregateNodes() throws Exception {
        var permissions = permissions();
        var all = permissions.get("invseeplusplus.*");
        assertEquals(PermissionDefault.FALSE, all.getDefault());
        for (String action : List.of("give", "clear", "clone")) {
            assertEquals(Boolean.TRUE, all.getChildren().get("invseeplusplus." + action + ".*"));
            var group = permissions.get("invseeplusplus." + action + ".*");
            assertEquals(PermissionDefault.FALSE, group.getDefault());
            for (String kind : List.of("inventory", "enderchest"))
                assertEquals(Boolean.TRUE, group.getChildren().get("invseeplusplus." + action + "." + kind));
        }
        for (String excluded : List.of("recovery", "reload", "tabcomplete"))
            assertFalse(all.getChildren().containsKey("invseeplusplus." + excluded));
    }
    @Test void originalCommandAliasesRemainAvailable() throws Exception {
        var commands = descriptor().getCommands();
        assertEquals(List.of("inventorysee", "isee"), commands.get("invsee").get("aliases"));
        assertEquals(List.of("enderchestsee", "esee"), commands.get("endersee").get("aliases"));
        for (String action : List.of("give", "clear", "clone")) {
            assertEquals(List.of("inventory" + action, "i" + action), commands.get("inv" + action).get("aliases"));
            assertEquals(List.of("enderchest" + action, "e" + action), commands.get("ender" + action).get("aliases"));
        }
    }
}

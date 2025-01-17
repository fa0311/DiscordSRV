/*-
 * LICENSE
 * DiscordSRV
 * -------------
 * Copyright (C) 2016 - 2021 Austin "Scarsz" Shapiro
 * -------------
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * END
 */

package github.scarsz.discordsrv.listeners;

import github.scarsz.discordsrv.Debug;
import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.api.events.DiscordConsoleCommandPostProcessEvent;
import github.scarsz.discordsrv.api.events.DiscordConsoleCommandPreProcessEvent;
import github.scarsz.discordsrv.util.DiscordUtil;
import github.scarsz.discordsrv.util.LangUtil;
import github.scarsz.discordsrv.util.PluginUtil;
import github.scarsz.discordsrv.util.TimeUtil;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.apache.commons.io.FileUtils;
import org.bukkit.Bukkit;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.InvalidPluginException;
import org.bukkit.plugin.Plugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@SuppressWarnings("ResultOfMethodCallIgnored")
public class DiscordConsoleListener extends ListenerAdapter {

    private List<String> allowedFileExtensions = new ArrayList<String>() {{
        add("jar");
        //add("zip"); todo support uploading compressed plugins & decompress
    }};

    @Override
    public void onGuildMessageReceived(GuildMessageReceivedEvent event) {
        // check if the server hasn't started yet but someone still tried to run a command...
        if (DiscordUtil.getJda() == null) return;
        // if message is from null author or self do not process
        if (event.getAuthor().getId().equals(DiscordUtil.getJda().getSelfUser().getId())) return;
        // only do anything with the messages if it's in the console channel
        if (DiscordSRV.getPlugin().getConsoleChannel() == null || !event.getChannel().getId().equals(DiscordSRV.getPlugin().getConsoleChannel().getId()))
            return;
        // block bots
        if (DiscordSRV.config().getBoolean("DiscordConsoleChannelBlockBots") && event.getAuthor().isBot()) {
            DiscordSRV.debug(Debug.UNCATEGORIZED, "Received a message from a bot in the console channel, but DiscordConsoleChannelBlockBots is enabled");
            return;
        }

        // handle all attachments
        for (Message.Attachment attachment : event.getMessage().getAttachments()) handleAttachment(event, attachment);

        boolean isWhitelist = DiscordSRV.config().getBoolean("DiscordConsoleChannelBlacklistActsAsWhitelist");
        List<String> blacklistedCommands = DiscordSRV.config().getStringList("DiscordConsoleChannelBlacklistedCommands");

        for (int i = 0; i < blacklistedCommands.size(); i++) blacklistedCommands.set(i, blacklistedCommands.get(i).toLowerCase());

        String requestedCommand = event.getMessage().getContentRaw().trim().split(" ")[0].toLowerCase();
        requestedCommand = requestedCommand.substring(requestedCommand.lastIndexOf(":") + 1);
        if (isWhitelist != blacklistedCommands.contains(requestedCommand)) return;

        // log command to console log file, if this fails the command is not executed for safety reasons unless this is turned off
        File logFile = DiscordSRV.getPlugin().getLogFile();
        if (logFile != null) {
            try {
                FileUtils.writeStringToFile(
                        logFile,
                        "[" + TimeUtil.timeStamp() + " | ID " + event.getAuthor().getId() + "] " + event.getAuthor().getName() + ": " + event.getMessage().getContentRaw() + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        true
                );
            } catch (IOException e) {
                DiscordSRV.error(LangUtil.InternalMessage.ERROR_LOGGING_CONSOLE_ACTION + " " + logFile.getAbsolutePath() + ": " + e.getMessage());
                if (DiscordSRV.config().getBoolean("CancelConsoleCommandIfLoggingFailed")) return;
            }
        }

        DiscordConsoleCommandPreProcessEvent consoleEvent = DiscordSRV.api.callEvent(new DiscordConsoleCommandPreProcessEvent(event, event.getMessage().getContentRaw(), true));

        // stop the command from being run if an API user cancels the event
        if (consoleEvent.isCancelled()) return;

        Bukkit.getScheduler().runTask(DiscordSRV.getPlugin(), () -> {
            DiscordSRV.getPlugin().getConsoleAppender().dumpStack();
            Bukkit.getServer().dispatchCommand(Bukkit.getServer().getConsoleSender(), consoleEvent.getCommand());
        });

        DiscordSRV.api.callEvent(new DiscordConsoleCommandPostProcessEvent(event, consoleEvent.getCommand(), true));
    }

    private void handleAttachment(GuildMessageReceivedEvent event, Message.Attachment attachment) {

        String[] attachmentSplit = attachment.getFileName().split("\\.");
        String attachmentExtension = attachmentSplit[attachmentSplit.length - 1];

        if (!allowedFileExtensions.contains(attachmentExtension)) {
            DiscordUtil.sendMessage(event.getChannel(), "Attached file \"" + attachment.getFileName() + "\" is of non-plugin extension " + attachmentExtension + ".");
            return;
        }

        if (DiscordSRV.isFileSystemLimited()) {
            throw new UnsupportedOperationException("File system access has been limited, can't process attachment.");
        }

        File pluginDestination = new File(DiscordSRV.getPlugin().getDataFolder().getParentFile(), attachment.getFileName());

        if (pluginDestination.exists()) {
            String pluginName = null;
            try {
                ZipFile jarZipFile = new ZipFile(pluginDestination);
                Enumeration<? extends ZipEntry> entries = jarZipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    pluginName = getPluginName(pluginName, jarZipFile, entry);
                }
                jarZipFile.close();
            } catch (IOException e) {
                DiscordUtil.sendMessage(event.getChannel(), "Failed loading plugin " + attachment.getFileName() + ": " + e.getMessage());
                DiscordSRV.warning(LangUtil.InternalMessage.FAILED_LOADING_PLUGIN + " " + attachment.getFileName() + ": " + e.getMessage());
                pluginDestination.delete();
                return;
            }
            if (pluginName == null) {
                DiscordUtil.sendMessage(event.getChannel(), "Attached file \"" + attachment.getFileName() + "\" either did not have a plugin.yml or it's plugin.yml did not contain it's name.");
                DiscordSRV.warning(LangUtil.InternalMessage.FAILED_LOADING_PLUGIN + " " + attachment.getFileName() + ": Attached file \"" + attachment.getFileName() + "\" either did not have a plugin.yml or it's plugin.yml did not contain it's name.");
                pluginDestination.delete();
                return;
            }

            Plugin plugin = Bukkit.getPluginManager().getPlugin(pluginName);

            PluginUtil.unloadPlugin(plugin);
            if (!pluginDestination.delete()) {
                DiscordUtil.sendMessage(event.getChannel(), "Failed deleting existing plugin");
                return;
            }
        }

        // download plugin jar from Discord
        attachment.downloadToFile(pluginDestination);

        String pluginName = null;
        try {
            ZipFile jarZipFile = new ZipFile(pluginDestination);
            while (jarZipFile.entries().hasMoreElements()) {
                ZipEntry entry = jarZipFile.entries().nextElement();
                pluginName = getPluginName(pluginName, jarZipFile, entry);
            }
            jarZipFile.close();
        } catch (IOException e) {
            DiscordUtil.sendMessage(event.getChannel(), "Failed loading plugin " + attachment.getFileName() + ": " + e.getMessage());
            DiscordSRV.warning(LangUtil.InternalMessage.FAILED_LOADING_PLUGIN + " " + attachment.getFileName() + ": " + e.getMessage());
            pluginDestination.delete();
            return;
        }

        if (pluginName == null) {
            DiscordUtil.sendMessage(event.getChannel(), "Attached file \"" + attachment.getFileName() + "\" either did not have a plugin.yml or it's plugin.yml did not contain it's name.");
            DiscordSRV.warning(LangUtil.InternalMessage.FAILED_LOADING_PLUGIN + " " + attachment.getFileName() + ": Attached file \"" + attachment.getFileName() + "\" either did not have a plugin.yml or it's plugin.yml did not contain it's name.");
            pluginDestination.delete();
            return;
        }

        Plugin loadedPlugin = Bukkit.getPluginManager().getPlugin(pluginName);

        if (loadedPlugin != null) {
            Bukkit.getPluginManager().disablePlugin(loadedPlugin);
            PluginUtil.unloadPlugin(loadedPlugin);
        }
        loadedPlugin = Bukkit.getPluginManager().getPlugin(pluginName);
        if (loadedPlugin != null) {
            DiscordUtil.sendMessage(event.getChannel(), "Attached file \"" + attachment.getFileName() + "\" either did not have a plugin.yml or it's plugin.yml did not contain it's name.");
            DiscordSRV.warning(LangUtil.InternalMessage.FAILED_LOADING_PLUGIN + " " + attachment.getFileName() + ": Attached file \"" + attachment.getFileName() + "\" either did not have a plugin.yml or it's plugin.yml did not contain it's name.");
            pluginDestination.delete();
            return;
        }

        try {
            loadedPlugin = Bukkit.getPluginManager().loadPlugin(pluginDestination);
        } catch (InvalidPluginException | InvalidDescriptionException e) {
            DiscordUtil.sendMessage(event.getChannel(), "Failed loading plugin " + attachment.getFileName() + ": " + e.getMessage());
            DiscordSRV.warning(LangUtil.InternalMessage.FAILED_LOADING_PLUGIN + " " + attachment.getFileName() + ": " + e.getMessage());
            pluginDestination.delete();
            return;
        }

        if (loadedPlugin != null) {
            Bukkit.getPluginManager().enablePlugin(loadedPlugin);
        }

        DiscordUtil.sendMessage(event.getChannel(), "Finished installing plugin " + attachment.getFileName() + " " + loadedPlugin + ".");
    }

    private String getPluginName(String pluginName, ZipFile jarZipFile, ZipEntry entry) throws IOException {
        if (!entry.getName().equalsIgnoreCase("plugin.yml")) return pluginName;
        BufferedReader reader = new BufferedReader(new InputStreamReader(jarZipFile.getInputStream(entry)));
        for (String line : reader.lines().collect(Collectors.toList()))
            if (line.trim().startsWith("name:"))
                pluginName = line.replace("name:", "").trim();
        return pluginName;
    }
}

/*
 * This file is part of BlueMap, licensed under the MIT License (MIT).
 *
 * Copyright (c) Blue (Lukas Rieger) <https://bluecolored.de>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package de.bluecolored.bluemap.forge;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import org.apache.logging.log4j.LogManager;

import com.flowpowered.math.vector.Vector3i;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import de.bluecolored.bluemap.common.plugin.Plugin;
import de.bluecolored.bluemap.common.plugin.commands.Commands;
import de.bluecolored.bluemap.common.plugin.serverinterface.ServerEventListener;
import de.bluecolored.bluemap.common.plugin.serverinterface.ServerInterface;
import de.bluecolored.bluemap.core.logger.Logger;
import net.minecraft.command.CommandSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.DimensionType;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.server.FMLServerStartingEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppingEvent;

@Mod(Plugin.PLUGIN_ID)
public class ForgeMod implements ServerInterface {
	
	private Plugin bluemap;
	private Commands<CommandSource> commands;
	private Map<String, UUID> worldUUIDs;
	private Collection<ServerEventListener> eventListeners;
	
	private LoadingCache<ServerWorld, UUID> worldUuidCache;
	
	public ForgeMod() {
		Logger.global = new Log4jLogger(LogManager.getLogger(Plugin.PLUGIN_NAME));
		
		this.bluemap = new Plugin("forge", this);
		this.worldUUIDs = new HashMap<>();
		this.eventListeners = new ArrayList<>(1);
		this.worldUuidCache = CacheBuilder.newBuilder()
				.weakKeys()
				.maximumSize(1000)
				.build(new CacheLoader<ServerWorld, UUID>() {
					@Override
					public UUID load(ServerWorld key) throws Exception {
						return loadUUIDForWorld(key);
					}
				});
		
		MinecraftForge.EVENT_BUS.register(this);
	}
	
	@SubscribeEvent
    public void onServerStarting(FMLServerStartingEvent event) {
		this.worldUUIDs.clear();
		
		for (ServerWorld world : event.getServer().getWorlds()) {
			try {
				registerWorld(world);
			} catch (IOException e) {
				Logger.global.logError("Failed to register world: " + world.getProviderName(), e);
			}
			
			try {
				world.save(null, false, false);
			} catch (Throwable t) {
				Logger.global.logError("Failed to save world: " + world.getProviderName(), t);
			}
		}

		//register commands
		this.commands = new Commands<>(bluemap, event.getServer().getCommandManager().getDispatcher(), forgeSource -> new ForgeCommandSource(this, bluemap, forgeSource));
		
		new Thread(() -> {
			try {
				Logger.global.logInfo("Loading...");
				bluemap.load();
				if (bluemap.isLoaded()) Logger.global.logInfo("Loaded!");
			} catch (Throwable t) {
				Logger.global.logError("Failed to load!", t);
			}
		}).start();
    }
	
	private void registerWorld(ServerWorld world) throws IOException {
		getUUIDForWorld(world);
	}
	
	@SubscribeEvent
    public void onServerStopping(FMLServerStoppingEvent event) {
		Logger.global.logInfo("Stopping...");
		bluemap.unload();
		Logger.global.logInfo("Saved and stopped!");
    }

	@Override
	public void registerListener(ServerEventListener listener) {
		eventListeners.add(listener);
	}

	@Override
	public void unregisterAllListeners() {
		eventListeners.clear();
	}
	
	@SubscribeEvent
	public void onBlockBreak(BlockEvent.BreakEvent evt) {
		onBlockChange(evt);
	}
	
	@SubscribeEvent
	public void onBlockPlace(BlockEvent.EntityPlaceEvent evt) {
		onBlockChange(evt);
	}
	
	private void onBlockChange(BlockEvent evt) {
		if (!(evt.getWorld() instanceof ServerWorld)) return;
		
		try {
			UUID world = getUUIDForWorld((ServerWorld) evt.getWorld());
			Vector3i position = new Vector3i(
					evt.getPos().getX(),
					evt.getPos().getY(),
					evt.getPos().getZ()
				);
			
			for (ServerEventListener listener : eventListeners) listener.onBlockChange(world, position);
			
		} catch (IOException ignore) {}
	}
	
	@SubscribeEvent
	public void onWorldSave(WorldEvent.Save evt) {
		if (!(evt.getWorld() instanceof ServerWorld)) return;
		
		try {
			UUID world = getUUIDForWorld((ServerWorld) evt.getWorld());
			
			for (ServerEventListener listener : eventListeners) listener.onWorldSaveToDisk(world);
			
		} catch (IOException ignore) {}
	}

	@Override
	public UUID getUUIDForWorld(File worldFolder) throws IOException {
		synchronized (worldUUIDs) {
			String key = worldFolder.getCanonicalPath();
			
			UUID uuid = worldUUIDs.get(key);
			if (uuid == null) {
				throw new IOException("There is no world with this folder loaded: " + worldFolder.getPath());
			}
			
			return uuid;
		}
	}
	
	public UUID getUUIDForWorld(ServerWorld world) throws IOException {
		try {
			return worldUuidCache.get(world);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof IOException) throw (IOException) cause;
			else throw new IOException(cause);
		}
	}
	
	private UUID loadUUIDForWorld(ServerWorld world) throws IOException {
		synchronized (worldUUIDs) {
			String key = getFolderForWorld(world).getPath();
			
			UUID uuid = worldUUIDs.get(key);
			if (uuid == null) {
				uuid = UUID.randomUUID();
				worldUUIDs.put(key, uuid);
			}
			
			return uuid;
		}
	}
	
	private File getFolderForWorld(ServerWorld world) throws IOException {
		MinecraftServer server = world.getServer();
		String worldName = server.func_240793_aU_().getWorldName();
		File worldFolder = new File(world.getServer().getDataDirectory(), worldName);
		File dimensionFolder = DimensionType.func_236031_a_(world.func_234923_W_(), worldFolder);
		return dimensionFolder.getCanonicalFile();
	}

	@Override
	public File getConfigFolder() {
		return new File("config/bluemap");
	}

	public Commands<CommandSource> getCommands() {
		return commands;
	}
    
}

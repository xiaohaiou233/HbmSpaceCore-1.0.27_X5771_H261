package com.hbm.commands;

import java.util.Arrays;

import com.hbm.dim.spawn.SpawnArmorTemplate;
import com.hbm.main.MainRegistry;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.WorldServer;

// ==== [modpack] 测试指令：触发降落/重生测试用，内容随开发实时修改
public class CommandHbmTest extends CommandBase {

	@Override
	public String getCommandName() {
		return "hbmtest";
	}

	@Override
	public String getCommandUsage(ICommandSender sender) {
		return "/hbmtest <0|1|2|gold|wipe|armor>";
	}

	@Override
	public void processCommand(ICommandSender sender, String[] args) {
		// [modpack] 调试：确认指令是否执行
		MainRegistry.logger.info("[hbmtest] executing, args=" + Arrays.toString(args));

		if(args.length == 0) return;

		EntityPlayerMP player = getCommandSenderAsPlayer(sender);

		// [modpack] 测试：/hbmtest 1 → 把玩家降落值设为 true（模拟已降落火星，重生效果测试用）
		if(args[0].equals("1")) {
			// [modpack] 降落点 = Duna (0,0)，y 固定 100（不能用 getTopSolidOrLiquidBlock：会同步生成 Duna chunk 卡服务器主线程）
			com.hbm.dim.spawn.SpawnTeleporter.setLanded(player, 0, 100, 0);
			MainRegistry.logger.info("[hbmtest] setLanded done, hasLanded=" + com.hbm.dim.spawn.SpawnTeleporter.hasLanded(player));
			sender.addChatMessage(new ChatComponentText("§a[hbmtest] hasLanded=true 降落点(0,100,0)"));
		} else if(args[0].equals("0")) {
			player.getEntityData().setBoolean("hbmcore_hasLanded", false);
			MainRegistry.logger.info("[hbmtest] reset, hasLanded=false");
			sender.addChatMessage(new ChatComponentText("§a[hbmtest] hasLanded=false"));
		} else if(args[0].equals("2")) {
			// [modpack] 重建空间站：在玩家网格中心重新搭建站体（改对齐常量 STATION_SPAWN_TILE_X/Z 后验证用）
			WorldServer spawnWorld = MinecraftServer.getServer().worldServerForDimension(com.hbm.config.SpaceConfig.spawnDimension);
			com.hbm.dim.spawn.SpawnTeleporter.ensureGrid(player);
			int sx = com.hbm.dim.spawn.SpawnTeleporter.getSpawnX(player);
			int sz = com.hbm.dim.spawn.SpawnTeleporter.getSpawnZ(player);
			com.hbm.dim.spawn.SpawnTeleporter.buildSpawnStation(spawnWorld, sx, sz);
			MainRegistry.logger.info("[hbmtest] station rebuilt at (" + sx + ",100," + sz + ")");
			sender.addChatMessage(new ChatComponentText("§a[hbmtest] station rebuilt at " + sx + ",100," + sz));
		} else if(args[0].equals("gold")) {
			// [modpack] 找出生点金块标记：扫描玩家周围 48 格内的金块并报告坐标（空间站对齐用）
			int px = (int)player.posX, py = (int)player.posY, pz = (int)player.posZ;
			int found = 0;
			for(int x = px - 48; x <= px + 48; x++) {
				for(int y = py - 48; y <= py + 48; y++) {
					for(int z = pz - 48; z <= pz + 48; z++) {
						if(player.worldObj.getBlock(x, y, z) == net.minecraft.init.Blocks.gold_block) {
							MainRegistry.logger.info("[hbmtest] gold block at (" + x + "," + y + "," + z + ")");
							sender.addChatMessage(new ChatComponentText("§e[hbmtest] gold at " + x + "," + y + "," + z));
							found++;
						}
					}
				}
			}
			if(found == 0) sender.addChatMessage(new ChatComponentText("§c[hbmtest] no gold block nearby"));
			else sender.addChatMessage(new ChatComponentText("§a[hbmtest] " + found + " gold block(s)"));
		} else if(args[0].equals("wipe")) {
			// [modpack] 清掉网格中心站体区域（改对齐常量后旧站体还在，先 wipe 再 /hbmtest 2 重建）
			// 盒子覆盖新旧两种站体占位 + 金块标记（会一并清掉）
			WorldServer sw = MinecraftServer.getServer().worldServerForDimension(com.hbm.config.SpaceConfig.spawnDimension);
			com.hbm.dim.spawn.SpawnTeleporter.ensureGrid(player);
			int sx = com.hbm.dim.spawn.SpawnTeleporter.getSpawnX(player);
			int sz = com.hbm.dim.spawn.SpawnTeleporter.getSpawnZ(player);
			int n = 0;
			for(int x = sx - 16; x <= sx + 20; x++)
			for(int y = 95; y <= 112; y++)
			for(int z = sz - 10; z <= sz + 18; z++) {
				if(!sw.isAirBlock(x, y, z)) {
					sw.setBlockToAir(x, y, z);
					n++;
				}
			}
			MainRegistry.logger.info("[hbmtest] wiped " + n + " blocks at grid center (" + sx + ",100," + sz + ")");
			sender.addChatMessage(new ChatComponentText("§a[hbmtest] wiped " + n + " blocks"));
		} else if(args[0].equals("armor")) {
			// [modpack] 进服护甲模板：快照当前穿戴 → 文件；首次加入按模板发装备（见 SpawnArmorTemplate）
			if(args.length < 2) {
				sender.addChatMessage(new ChatComponentText("§c用法: /hbmtest armor <save|show|clear|give>"));
			} else if(args[1].equals("save")) {
				int n = SpawnArmorTemplate.saveFromPlayer(player);
				if(n >= 0) sender.addChatMessage(new ChatComponentText("§a[hbmtest] 模板已保存 " + n + " 件（" + SpawnArmorTemplate.FILE_NAME + "）"));
				else sender.addChatMessage(new ChatComponentText("§c[hbmtest] 模板保存失败，见日志"));
			} else if(args[1].equals("show")) {
				sender.addChatMessage(new ChatComponentText("§e[hbmtest] " + SpawnArmorTemplate.describe()));
			} else if(args[1].equals("clear")) {
				boolean had = SpawnArmorTemplate.clear();
				sender.addChatMessage(new ChatComponentText(had ? "§a[hbmtest] 模板已删除" : "§e[hbmtest] 没有模板"));
			} else if(args[1].equals("give")) {
				boolean ok = SpawnArmorTemplate.applyToPlayer(player);
				sender.addChatMessage(new ChatComponentText(ok ? "§a[hbmtest] 已穿上模板护甲" : "§c[hbmtest] 无模板/未启用，未穿"));
			} else {
				sender.addChatMessage(new ChatComponentText("§c用法: /hbmtest armor <save|show|clear|give>"));
			}
		}
	}
}

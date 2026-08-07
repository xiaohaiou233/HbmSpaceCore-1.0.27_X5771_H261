package com.hbm.compat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.logging.log4j.Logger;

/**
 * [modpack] 配置文件 key 值修正工具。最小侵入:只替换目标 key 的值,保留其他行与注释。
 */
public final class CompatConfigUtil {

	private CompatConfigUtil() {}

	/**
	 * 确保 cfg 文件中 key 值为 expectedValue。文件不存在则安全跳过(返回 false)。
	 *
	 * @param cfgFile        目标 .cfg 文件(相对 configDir)
	 * @param key            要匹配的 key 名,如 "B:MeteoriteWorldGen"
	 * @param expectedValue  期望的值,如 "false"
	 * @param reason         注释标记原因,如 "perf: meteorite file IO every tick"
	 * @param logger         输出日志
	 * @return true=文件存在且已确保值;false=文件不存在/IO失败/key未找到
	 */
	public static boolean setKeyValue(File cfgFile, String key, String expectedValue, String reason, Logger logger) {
		if (!cfgFile.isFile()) {
			logger.warn("[hbmcore][compat] {} not found, skip (mod not installed or moved)", cfgFile.getPath());
			return false;
		}

		try {
			List<String> lines = new ArrayList<>(Files.readAllLines(cfgFile.toPath(), StandardCharsets.UTF_8));
			// group 1 = 行首空白 + key + 等号及周围空白;group 2 = "=" 后的值(不含行尾空白)
			String keyPattern = "^(\\s*" + Pattern.quote(key) + "\\s*=\\s*)(.*?)\\s*$";
			Pattern pat = Pattern.compile(keyPattern);

			int matchedIndex = -1;
			for (int i = 0; i < lines.size(); i++) {
				if (pat.matcher(lines.get(i)).matches()) {
					matchedIndex = i;
					break;
				}
			}

			if (matchedIndex == -1) {
				logger.warn("[hbmcore][compat] key '{}' not found in {}, config format changed?", key, cfgFile.getPath());
				return false;
			}

			String current = lines.get(matchedIndex);
			java.util.regex.Matcher m = pat.matcher(current);
			// 幂等:精确比较 "=" 后的值,避免行内注释含该值 / 值前缀(如 =10 vs "1")误判
			if (m.matches() && m.group(2).trim().equals(expectedValue)) {
				logger.info("[hbmcore][compat] {} in {} already = {}", key, cfgFile.getName(), expectedValue);
				return true;
			}

			// 替换值:保留 group 1(行首空白 + "B:MeteoriteWorldGen" + "=" + 周围空白),写入新值
			String newLine = m.group(1) + expectedValue;
			lines.set(matchedIndex, newLine);

			// 若该 key 行上方还没有 [hbmcore] 注释,则插入
			boolean hasMark = false;
			for (int i = 0; i < matchedIndex; i++) {
				if (lines.get(i).contains("[hbmcore]")) {
					hasMark = true;
					break;
				}
			}
			if (!hasMark) {
				lines.add(matchedIndex, "# [hbmcore] " + reason);
			}

			Files.write(cfgFile.toPath(), lines, StandardCharsets.UTF_8);
			logger.info("[hbmcore][compat] set {} = {} in {} ({})", key, expectedValue, cfgFile.getName(), reason);
			return true;
		} catch (IOException e) {
			logger.warn("[hbmcore][compat] IO error on {}: {}", cfgFile.getPath(), e.toString());
			return false;
		}
	}
}

//! HLS (m3u8) 视频下载：解析播放列表，下载分片并合并成一个 .ts 文件。
//!
//! v1 范围：点播（VOD）播放列表；主播放列表自动选最高码率；支持 AES-128
//! 分片加密（#EXT-X-KEY）和 BYTERANGE 分片；直播（无 ENDLIST）只下载本次
//! 取到的分片；fMP4（EXT-X-MAP）暂不支持。输出是拼接的 MPEG-TS，可用
//! VLC / MX Player 等播放。下载不走全局限速（走自己的 reqwest 客户端）。

use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Instant;

use aes::Aes128;
use cbc::cipher::block_padding::Pkcs7;
use cbc::cipher::{BlockDecryptMut, KeyIvInit};
use cbc::Decryptor;
use m3u8_rs::{KeyMethod, Playlist};
use tokio::fs::{File, OpenOptions};
use tokio::io::AsyncWriteExt;

/// 取消时返回的错误文本，lib.rs 靠它把任务标成“已暂停”。
pub const ERR_CANCELLED: &str = "下载已取消";

/// 断点续传进度文件：放在输出文件旁边，内容是三行：
/// 播放列表地址、分片总数、已写完的分片数。
/// 任务失败或取消时保留，下次继续时从这里接着下；
/// 任务完成或删除时删掉。地址或总数对不上就从头开始。
pub fn progress_path(out_path: &Path) -> PathBuf {
    PathBuf::from(format!("{}.hlsprogress", out_path.display()))
}

fn is_cancelled(cancel: &Arc<AtomicBool>) -> bool {
    cancel.load(Ordering::Relaxed)
}

/// 请求带重试：IPFS 这类网关偶尔会中途断开，一个分片失败就整个任务失败；
/// 每个请求最多试 5 次，失败后等 1 秒、2 秒、4 秒、8 秒再试。
async fn fetch_bytes(
    client: &reqwest::Client,
    url: &str,
    range: Option<(u64, u64)>,
) -> Result<Vec<u8>, String> {
    let mut last_err = String::new();
    for attempt in 1..=5 {
        match fetch_bytes_once(client, url, range).await {
            Ok(b) => return Ok(b),
            Err(e) => {
                last_err = e;
                if attempt < 5 {
                    let wait = 1u64 << (attempt - 1); // 1、2、4、8 秒
                    log::warn!("[HLS] 请求失败，第 {attempt}/5 次重试 {url}: {last_err}（{wait} 秒后）");
                    tokio::time::sleep(std::time::Duration::from_secs(wait)).await;
                }
            }
        }
    }
    Err(last_err)
}

async fn fetch_bytes_once(
    client: &reqwest::Client,
    url: &str,
    range: Option<(u64, u64)>,
) -> Result<Vec<u8>, String> {
    let mut req = client.get(url);
    if let Some((start, end)) = range {
        req = req.header("Range", format!("bytes={start}-{end}"));
    }
    let resp = req
        .send()
        .await
        .map_err(|e| format!("请求失败 {url}: {e}"))?;
    let status = resp.status();
    if range.is_some() && status.as_u16() != 206 {
        return Err(format!("请求失败 {url}: 服务器不支持 Range 请求（HTTP {status}）"));
    }
    if !status.is_success() {
        return Err(format!("请求失败 {url}: HTTP {status}"));
    }
    resp.bytes()
        .await
        .map(|b| b.to_vec())
        .map_err(|e| format!("读取响应失败 {url}: {e}"))
}

fn resolve(base: &url::Url, rel: &str) -> Result<url::Url, String> {
    base.join(rel)
        .map_err(|e| format!("解析相对地址失败 {rel}: {e}"))
}

fn parse_iv(hex: &str) -> Result<[u8; 16], String> {
    let h = hex.strip_prefix("0x").unwrap_or(hex);
    if h.len() != 32 {
        return Err(format!("IV 长度错误（应为 32 个十六进制字符）: {hex}"));
    }
    let bytes = (0..h.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&h[i..i + 2], 16))
        .collect::<Result<Vec<u8>, _>>()
        .map_err(|_| format!("IV 格式错误: {hex}"))?;
    let mut iv = [0u8; 16];
    iv.copy_from_slice(&bytes);
    Ok(iv)
}

fn seq_iv(seq: u64) -> [u8; 16] {
    // 没有 IV 时按 HLS 规范用 Media Sequence Number 做 IV（128 位大端）。
    let mut iv = [0u8; 16];
    iv[8..].copy_from_slice(&seq.to_be_bytes());
    iv
}

/// 有些站点的密钥地址返回的是 32 个十六进制字符（ppnix 就是）而不是原始 16 字节，
/// 遇到就转成 16 字节；标准站点直接返回 16 字节的保持原样。
fn normalize_key(b: Vec<u8>) -> Result<Vec<u8>, String> {
    if b.len() == 16 {
        return Ok(b);
    }
    // 注意：32 个十六进制字符的密钥不要按 hex 解码！
    // 实测 ppnix（https://www.ppnix.com/info/m3u8/key）返回 32 位 hex 字符串
    // "ba9bf05693b9fa202d922dd43a08f281"，按 hex 解码成 16 字节去解密，
    // 解出来是乱码；取前 16 个 ASCII 字符（"ba9bf05693b9fa20"）当 AES-128
    // 密钥，解出来 100/100 个 TS 包都有 0x47 同步头，才是对的。
    // 所以这里直接取前 16 个字节。
    if b.len() == 32 && b.iter().all(|c| c.is_ascii_hexdigit()) {
        return Ok(b[..16].to_vec());
    }
    Err(format!(
        "密钥长度错误（应为 16 字节或 32 个十六进制字符）：{} 字节",
        b.len()
    ))
}

fn decrypt_aes128(key: &[u8], iv: &[u8; 16], mut data: Vec<u8>) -> Result<Vec<u8>, String> {
    if key.len() != 16 {
        return Err(format!("密钥长度错误（应为 16 字节）：{} 字节", key.len()));
    }
    let decryptor = Decryptor::<Aes128>::new(key.into(), iv.into());
    decryptor
        .decrypt_padded_vec_mut::<Pkcs7>(&mut data)
        .map_err(|e| format!("AES-128 解密失败: {e}"))
}

/// 一个分片的下载计划（下载前全部算好：地址、Range、解密密钥和 IV）。
struct SegPlan {
    url: String,
    range: Option<(u64, u64)>,
    key: Option<(Vec<u8>, [u8; 16])>,
}

/// 下载一个 m3u8 视频：拉播放列表 → 选清晰度 → 下分片（可解密）→ 合并成 .ts。
///
/// `on_progress(已下字节, 预估总字节, 平均速度 B/s)`，总字节未知时为 0。
/// `remove_ads` 开时：同一列表再拉一次，两次不一样的分片就是服务器动态
/// 插进去的广告，跳过不下（实测：两次拉取除了 5 个广告分片外完全一样，
/// 广告位置每次都变；两次一致或差异太多时不过滤，全部下载）。
/// `parallel_segments` 是同时下载的分片数（设置里可改，默认 8）。
pub async fn download_hls(
    client: &reqwest::Client,
    playlist_url: &str,
    out_path: &Path,
    cancel: &Arc<AtomicBool>,
    mut on_progress: impl FnMut(u64, u64, u64),
    remove_ads: bool,
    parallel_segments: usize,
) -> Result<(), String> {
    let base = url::Url::parse(playlist_url)
        .map_err(|_| format!("无效的播放列表地址: {playlist_url}"))?;

    // 1. 拉播放列表；主播放列表最多再跟一层，选最高码率。
    let mut playlist = fetch_and_parse(client, &base).await?;
    let mut media_url = base.clone();
    let mut bandwidth: Option<u64> = None;
    for _ in 0..2 {
        match playlist {
            Playlist::MasterPlaylist(master) => {
                let variant = master
                    .variants
                    .iter()
                    .filter(|v| !v.is_i_frame)
                    .max_by_key(|v| v.bandwidth)
                    .ok_or_else(|| "主播放列表没有可用的清晰度".to_string())?;
                bandwidth = Some(variant.bandwidth);
                let variant_url = resolve(&base, &variant.uri)?;
                log::info!("[HLS] 选清晰度: 码率 {} bps, {}", variant.bandwidth, variant_url);
                media_url = variant_url.clone();
                playlist = fetch_and_parse(client, &variant_url).await?;
            }
            Playlist::MediaPlaylist(_) => break,
        }
    }
    let mut media = match playlist {
        Playlist::MediaPlaylist(m) => m,
        Playlist::MasterPlaylist(_) => {
            return Err("主播放列表嵌套太深，未找到媒体播放列表".to_string());
        }
    };

    if media.segments.is_empty() {
        return Err("播放列表里没有分片".to_string());
    }
    if media.segments.iter().any(|s| s.map.is_some()) {
        return Err("暂不支持 fMP4（EXT-X-MAP）格式的 m3u8".to_string());
    }
    if !media.end_list {
        log::info!("[HLS] 直播播放列表：只下载本次取到的分片");
    }

    // 去广告（设置里开）：同一列表再拉一次，两次不一样的分片就是服务器
    // 动态插进去的广告，跳过不下。两次一致就不过滤；差异太多（超过 10%）
    // 说明列表对不上，也不过滤，全部下载，宁可多下也不错下。
    if remove_ads {
        match fetch_and_parse(client, &media_url).await {
            Ok(Playlist::MediaPlaylist(second)) => {
                let urls2: HashSet<String> = second
                    .segments
                    .iter()
                    .filter_map(|s| resolve(&base, &s.uri).ok().map(|u| u.to_string()))
                    .collect();
                let before = media.segments.len();
                let removed = media
                    .segments
                    .iter()
                    .filter(|s| {
                        resolve(&base, &s.uri)
                            .map(|u| !urls2.contains(&u.to_string()))
                            .unwrap_or(false)
                    })
                    .count();
                if removed > 0 && removed * 10 <= before {
                    media.segments.retain(|s| {
                        resolve(&base, &s.uri)
                            .map(|u| urls2.contains(&u.to_string()))
                            .unwrap_or(true)
                    });
                    log::info!("[HLS] 去广告：跳过 {removed} 个广告分片（共 {before} 个）");
                } else if removed > 0 {
                    // 差异太多说明两次列表对不上：不动原来的列表，全部下载。
                    log::info!("[HLS] 去广告：差异太多（{removed}/{before}），不过滤，全部下载");
                } else {
                    log::info!("[HLS] 去广告：两次列表一致，没有广告分片");
                }
            }
            Ok(_) => {
                log::info!("[HLS] 去广告：第二次拉取不是媒体播放列表，不过滤");
            }
            Err(e) => {
                log::info!("[HLS] 去广告：第二次拉取失败（{e}），不过滤");
            }
        }
    }

    // 预估总大小：码率 × 总时长 / 8；没有码率信息时总大小未知（传 0）。
    let total_duration: f32 = media.segments.iter().map(|s| s.duration).sum();
    let total_bytes: u64 = bandwidth
        .map(|b| (b as f64 * total_duration as f64 / 8.0) as u64)
        .unwrap_or(0);
    log::info!(
        "[HLS] 分片 {} 个，总时长 {:.0} 秒，预估 {} 字节",
        media.segments.len(),
        total_duration,
        total_bytes
    );

    // 2. 算好每个分片的下载计划：地址、Range（无 offset 时接着上一个）、密钥只取一次。
    //    按 HLS 规矩，一个 #EXT-X-KEY 管它后面所有分片，直到出现新的 KEY 或 METHOD=NONE，
    //    所以要把密钥往后带：分片自己带了密钥就用它的并记住，没带就沿用记住的。
    //    （之前只认分片自己带的密钥，解析器只给第一个分片带密钥时，后面的分片
    //    会按密文原文写进文件，任务还显示 COMPLETED——ppnix 就是这么坏的。）
    let mut key_cache: HashMap<String, Vec<u8>> = HashMap::new();
    let mut range_end: HashMap<String, u64> = HashMap::new();
    // 当前生效的密钥：(密钥字节, 标签里显式给的 IV；没有就是 None，表示按分片序号算)。
    // None 表示当前没有加密（还没见过 KEY，或刚见过 METHOD=NONE）。
    let mut current_key: Option<(Vec<u8>, Option<[u8; 16]>)> = None;
    let mut plans: Vec<SegPlan> = Vec::with_capacity(media.segments.len());
    for (i, seg) in media.segments.iter().enumerate() {
        let url = resolve(&base, &seg.uri)?;
        let range = seg.byte_range.as_ref().map(|br| {
            let start = br
                .offset
                .unwrap_or_else(|| range_end.get(&url.to_string()).copied().unwrap_or(0));
            range_end.insert(url.to_string(), start + br.length);
            (start, start + br.length - 1)
        });
        // 分片声明了密钥就更新当前密钥（换密钥、中途变明文都在这里处理）。
        if let Some(k) = &seg.key {
            match k.method {
                KeyMethod::AES128 => {
                    let key_uri = k
                        .uri
                        .clone()
                        .ok_or_else(|| "AES-128 未给出密钥地址".to_string())?;
                    let key_url = resolve(&base, &key_uri)?.to_string();
                    let key_bytes = match key_cache.get(&key_url) {
                        Some(b) => b.clone(),
                        None => {
                            let b = fetch_bytes(client, &key_url, None).await?;
                            let b = normalize_key(b)?;
                            key_cache.insert(key_url, b.clone());
                            b
                        }
                    };
                    let explicit_iv = match &k.iv {
                        Some(hex) => Some(parse_iv(hex)?),
                        None => None,
                    };
                    current_key = Some((key_bytes, explicit_iv));
                }
                KeyMethod::None => {
                    current_key = None;
                }
                _ => return Err("不支持的加密方式（只支持 AES-128）".to_string()),
            }
        }
        // 没声明密钥的分片沿用当前密钥；IV 有显式的就用显式的，没有按分片序号算。
        let key = match &current_key {
            Some((key_bytes, explicit_iv)) => {
                let seq = media.media_sequence + i as u64;
                let iv = match explicit_iv {
                    Some(iv) => *iv,
                    None => seq_iv(seq),
                };
                Some((key_bytes.clone(), iv))
            }
            None => None,
        };
        plans.push(SegPlan {
            url: url.to_string(),
            range,
            key,
        });
    }

    // 3. 断点续传：上次失败/取消后，输出文件和进度文件都还在，
    //    从上次写完的分片继续，不用从头再下。没有进度文件时从头开始。
    let progress_file = progress_path(out_path);
    let mut start_idx = 0usize;
    let mut downloaded: u64 = 0;
    if let Ok(txt) = std::fs::read_to_string(&progress_file) {
        let mut lines = txt.lines();
        let url_ok = lines.next() == Some(playlist_url);
        let count_ok = lines
            .next()
            .and_then(|s| s.trim().parse::<usize>().ok())
            == Some(plans.len());
        if url_ok && count_ok {
            if let Some(Ok(n)) = lines.next().map(|s| s.trim().parse::<usize>()) {
                if n >= plans.len() {
                    // 上次已全部写完，只差收尾：直接算完成。
                    let _ = std::fs::remove_file(&progress_file);
                    return Ok(());
                }
                if n > 0 {
                    if let Ok(meta) = std::fs::metadata(out_path) {
                        if meta.len() > 0 {
                            start_idx = n;
                            downloaded = meta.len();
                            log::info!(
                                "[HLS] 断点续传：从第 {}/{} 分片继续（已有 {} 字节）",
                                n,
                                plans.len(),
                                downloaded
                            );
                        }
                    }
                }
            }
        }
    }
    if start_idx == 0 {
        let _ = std::fs::remove_file(&progress_file);
    }
    let mut out = if start_idx > 0 {
        OpenOptions::new()
            .create(true)
            .append(true)
            .open(out_path)
            .await
            .map_err(|e| format!("打开输出文件失败: {e}"))?
    } else {
        File::create(out_path)
            .await
            .map_err(|e| format!("创建输出文件失败: {e}"))?
    };
    let started = Instant::now();
    let resume_base = downloaded; // 续传时已有字节不计入本次速度

    // 流水线提速：parallel_segments 个连接一直满着，一个分片下完立刻上下一个，
    // 不用等整组都下完；先到的分片先缓存，按顺序写文件（.ts 必须按顺序拼）。
    // 原来是 8 个一组 join_all，一组里最慢的那个拖住整组，其他连接干等。
    // 并发数为 0 会一个分片也下不下来，这里至少按 1 算（调用方也会 clamp）。
    let parallel_segments = parallel_segments.max(1);
    let mut set = tokio::task::JoinSet::new();
    let mut next_to_fetch = start_idx;
    let mut next_to_write = start_idx;
    let mut buffered: HashMap<usize, Vec<u8>> = HashMap::new();
    while next_to_write < plans.len() {
        // 有空位就补上下一个分片，保持 parallel_segments 个连接满着。
        while set.len() < parallel_segments && next_to_fetch < plans.len() {
            let idx = next_to_fetch;
            next_to_fetch += 1;
            let plan = &plans[idx];
            let client = client.clone();
            let url = plan.url.clone();
            let range = plan.range;
            let key = plan.key.clone();
            set.spawn(async move {
                let mut data = fetch_bytes(&client, &url, range).await?;
                if let Some((key_bytes, iv)) = key {
                    data = decrypt_aes128(&key_bytes, &iv, data)?;
                }
                Ok::<(usize, Vec<u8>), String>((idx, data))
            });
        }
        if is_cancelled(cancel) {
            return Err(ERR_CANCELLED.to_string());
        }
        match set.join_next().await {
            Some(Ok(Ok((idx, data)))) => {
                buffered.insert(idx, data);
                // 按顺序写出所有能写的分片。
                while let Some(data) = buffered.remove(&next_to_write) {
                    if is_cancelled(cancel) {
                        return Err(ERR_CANCELLED.to_string());
                    }
                    out.write_all(&data)
                        .await
                        .map_err(|e| format!("写入文件失败: {e}"))?;
                    downloaded += data.len() as u64;
                    next_to_write += 1;
                    let elapsed = started.elapsed().as_secs_f64().max(0.1);
                    on_progress(
                        downloaded,
                        total_bytes,
                        ((downloaded - resume_base) as f64 / elapsed) as u64,
                    );
                    // 每写完 parallel_segments 个或全部写完时记一次进度：这次失败的话，下次从这里继续。
                    if next_to_write % parallel_segments == 0 || next_to_write == plans.len() {
                        let _ = tokio::fs::write(
                            &progress_file,
                            format!("{}\n{}\n{}", playlist_url, plans.len(), next_to_write),
                        )
                        .await;
                        log::info!(
                            "[HLS] 已写 {} / {} 分片（{} 字节）",
                            next_to_write,
                            plans.len(),
                            downloaded
                        );
                    }
                }
            }
            Some(Ok(Err(e))) => return Err(e),
            Some(Err(e)) => return Err(format!("分片任务异常: {e}")),
            None => break,
        }
    }

    out.flush()
        .await
        .map_err(|e| format!("写入文件失败: {e}"))?;
    let _ = std::fs::remove_file(&progress_file);
    log::info!("[HLS] 完成: {} 字节 -> {:?}", downloaded, out_path);
    Ok(())
}

async fn fetch_and_parse(client: &reqwest::Client, url: &url::Url) -> Result<Playlist, String> {
    let bytes = fetch_bytes(client, url.as_str(), None).await?;
    m3u8_rs::parse_playlist_res(&bytes).map_err(|e| format!("解析播放列表失败 {url}: {e}"))
}

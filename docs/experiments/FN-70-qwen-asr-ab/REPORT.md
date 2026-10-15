# Qwen3-ASR versus current SenseVoice, controlled Japanese captions

Experimental cloud CPU evidence only; no production change or Android speed claim. See PROTOCOL.md for fixed source, runtime, segmentation and scoring limits.

## sensevoice
Runtime 1.13.8; model load 2.344s; process peakRSS 468.6MiB.
### emoji
{"runs": 3, "first_asr_rtf": 0.04316374312038189, "warm_asr_rtf_median": 0.040484966001224726, "cer": null, "first_segment_decode_s": 0.13053270000091288, "first_visible_estimate_warm_s": 5.410532700000913, "first_visible_estimate_cold_s": 7.754428551000529, "max_output_tokens": 87, "first_segment_input_ready_s": 5.28, "same_text_across_repeats": true, "segments": 13, "timestamp_count": 491}

Full initial-pass raw output (VAD envelope, not word-aligned subtitle timing):

2.982–5.030: このイラストの意味分かりますか？
6.278–16.966: 今、世界中で使われ ている の は何語でしょ うか 英語、スペイン語、中国語、それはビジネス文章では使われない言葉です。
17.190–19.398: 正解 は 絵文字。
21.606–32.678: 絵文字はインターネット上のコミュニケーションで使われ、特に若い世代に人気です 絵文字の映画がハリウッドで作られるほど。
33.638–36.710: 絵文字は日本発祥。
36.966–41.670: 絵を文字のように使う新しい表現スタイルです。
42.406–49.734: 有名なものといえ ば、スマイル、コマり顔、握りこ、パートなど。
50.086–53.670: 意味深なピザや ナスも。
55.750–70.534: 絵文字が若者に使われる理由は、驚きや喜びなどの感情を長い文章よりシンプルに伝えられるから 絵文字は昔から使われてきました 古代エジプトでも原始時代でも。
71.718–74.598: 絵は言葉の壁を越えます。
75.590–78.726: 瞬時 に 脳 が 認識 できる の です。
79.526–96.102: 例えば、円の中に斜め線、そして犬のマークがあれば、犬は禁止だとわかります あるいはフロッピーディスクのアイコンを見れば、文書の保存だと理解できます 実物を知らなくても大丈夫？
97.286–110.406: 絵文字の影響で増えるのは怠け者か人生の楽しみかはわかりませんが、いずれにせよオックスフォード辞書が選んだ2015年の言葉は手しなき そう、絵文字なんです。

Excluded VAD gaps: [{"start_seconds": 0.0, "end_seconds": 2.982, "rms_dbfs": -31.899211883544922}, {"start_seconds": 5.03, "end_seconds": 6.278, "rms_dbfs": -29.471839904785156}, {"start_seconds": 16.966, "end_seconds": 17.19, "rms_dbfs": -32.54572677612305}, {"start_seconds": 19.398, "end_seconds": 21.606, "rms_dbfs": -29.710044860839844}, {"start_seconds": 32.678, "end_seconds": 33.638, "rms_dbfs": -32.795570373535156}, {"start_seconds": 36.71, "end_seconds": 36.966, "rms_dbfs": -31.472190856933594}, {"start_seconds": 41.67, "end_seconds": 42.406, "rms_dbfs": -34.483009338378906}, {"start_seconds": 49.734, "end_seconds": 50.086, "rms_dbfs": -31.546646118164062}, {"start_seconds": 53.67, "end_seconds": 55.75, "rms_dbfs": -29.464710235595703}, {"start_seconds": 70.534, "end_seconds": 71.718, "rms_dbfs": -31.567626953125}, {"start_seconds": 74.598, "end_seconds": 75.59, "rms_dbfs": -27.271326065063477}, {"start_seconds": 78.726, "end_seconds": 79.526, "rms_dbfs": -32.05398941040039}, {"start_seconds": 96.102, "end_seconds": 97.286, "rms_dbfs": -33.863338470458984}, {"start_seconds": 110.406, "end_seconds": 113.96, "rms_dbfs": -32.50840759277344}]

### kishida
{"runs": 3, "first_asr_rtf": 0.05055973033794042, "warm_asr_rtf_median": 0.05037527603163999, "cer": null, "first_segment_decode_s": 0.21677202300088538, "first_visible_estimate_warm_s": 5.3047720230008855, "first_visible_estimate_cold_s": 7.648667874000501, "max_output_tokens": 109, "first_segment_input_ready_s": 5.088, "same_text_across_repeats": true, "segments": 12, "timestamp_count": 709}

Full initial-pass raw output (VAD envelope, not word-aligned subtitle timing):

1.286–4.838: はい、あの、まず昨日の事件については。
5.222–6.726: ？
7.270–23.270: 容疑者が介捕され、そして警察庁において捜査中です。また、私の方からあの会のご心配をおかけし、そしてご迷惑をおかけした地元の皆さんをや。
23.782–46.428: で漁業組合の方々ですとか、あるいは容疑者確去にご協力いただいた方に対し直接おの電話を入げさせていただきましたその他にも国国内また国外からもさ々なご心配やお見いの声をいただいたこと。
46.502–67.612: を感謝申し上げております。そして民主主義の根幹をなす選挙においてこうした暴力的な行為が行われたこのことは絶対許すことはできないと考えていますそして大切なことは。
67.686–69.318: この？
69.734–90.300: 大切な選挙を最後まででやり通すことであると思っています、有権者の皆さんの声をしっかり選挙を通じて示してもらうこと、これは我が国にとってもまた我が国の民主主義にとってもこれは大切なことであります。
90.374–114.044: そういった思いで国民の皆さんと共にこの大切な選挙を最後までやり通していきたいと考えています、 そして各党のこうした選挙運動が妨げられることがないよう 警察においては警備安全について万全を期してもらいたいと考えています。
114.630–123.046: ござ総理、朝日新聞です。は。あの、これから広島さにともありますけれども、警察の昨日の警備体制は十分だったというふうにお考えでしょうか。
123.942–144.476: 警備については今後今回の事件の捜査が進むと同時に検証も行われるもとだと思っています、いずれよサミトをはじめ、世界各国から要人が集われる。
144.550–169.276: こうした日程日本ては、日本全体として最大限警備安全に努めていかなければならないと思っていますぜ非国民の皆さんと共にこうした外交日程においても安全を確保できるように。
169.350–173.926: 力を尽くしていきたいと思って、います、はい、前の質。

Excluded VAD gaps: [{"start_seconds": 0.0, "end_seconds": 1.286, "rms_dbfs": -58.10186767578125}, {"start_seconds": 4.838, "end_seconds": 5.222, "rms_dbfs": -32.40620422363281}, {"start_seconds": 6.726, "end_seconds": 7.27, "rms_dbfs": -43.871681213378906}, {"start_seconds": 23.27, "end_seconds": 23.782, "rms_dbfs": -25.745601654052734}, {"start_seconds": 46.428, "end_seconds": 46.502, "rms_dbfs": -44.607215881347656}, {"start_seconds": 67.612, "end_seconds": 67.686, "rms_dbfs": -41.6379508972168}, {"start_seconds": 69.318, "end_seconds": 69.734, "rms_dbfs": -37.4284782409668}, {"start_seconds": 90.3, "end_seconds": 90.374, "rms_dbfs": -42.490806579589844}, {"start_seconds": 114.044, "end_seconds": 114.63, "rms_dbfs": -42.085609436035156}, {"start_seconds": 123.046, "end_seconds": 123.942, "rms_dbfs": -41.09226608276367}, {"start_seconds": 144.476, "end_seconds": 144.55, "rms_dbfs": -43.280033111572266}, {"start_seconds": 169.276, "end_seconds": 169.35, "rms_dbfs": -40.84534454345703}, {"start_seconds": 173.926, "end_seconds": 175.7866875, "rms_dbfs": -63.02162551879883}]

### kishida114
{"runs": 3, "first_asr_rtf": 0.04843516136842434, "warm_asr_rtf_median": 0.04706713642983168, "cer": 0.08256880733944955, "first_segment_decode_s": 0.1682435530001385, "first_visible_estimate_warm_s": 5.256243553000139, "first_visible_estimate_cold_s": 7.600139403999754, "max_output_tokens": 109, "first_segment_input_ready_s": 5.088, "same_text_across_repeats": true, "segments": 8, "timestamp_count": 466}

Full initial-pass raw output (VAD envelope, not word-aligned subtitle timing):

1.286–4.838: はい、あの、まず昨日の事件については。
5.222–6.726: ？
7.270–23.270: 容疑者が介捕され、そして警察庁において捜査中です。また、私の方からあの会のご心配をおかけし、そしてご迷惑をおかけした地元の皆さんをや。
23.782–46.428: で漁業組合の方々ですとか、あるいは容疑者確去にご協力いただいた方に対し直接おの電話を入げさせていただきましたその他にも国国内また国外からもさ々なご心配やお見いの声をいただいたこと。
46.502–67.612: を感謝申し上げております。そして民主主義の根幹をなす選挙においてこうした暴力的な行為が行われたこのことは絶対許すことはできないと考えていますそして大切なことは。
67.686–69.318: この？
69.734–90.300: 大切な選挙を最後まででやり通すことであると思っています、有権者の皆さんの声をしっかり選挙を通じて示してもらうこと、これは我が国にとってもまた我が国の民主主義にとってもこれは大切なことであります。
90.374–114.000: そういった思いで国民の皆さんと共もにこの大切な選挙を最後までやり通していきたいと考えています そして各党のこうした選挙運動が妨げられることがないよう 警察においては警備安全について万全を期してもらいたいと考えています。

Excluded VAD gaps: [{"start_seconds": 0.0, "end_seconds": 1.286, "rms_dbfs": -58.10186767578125}, {"start_seconds": 4.838, "end_seconds": 5.222, "rms_dbfs": -32.40620422363281}, {"start_seconds": 6.726, "end_seconds": 7.27, "rms_dbfs": -43.871681213378906}, {"start_seconds": 23.27, "end_seconds": 23.782, "rms_dbfs": -25.745601654052734}, {"start_seconds": 46.428, "end_seconds": 46.502, "rms_dbfs": -44.607215881347656}, {"start_seconds": 67.612, "end_seconds": 67.686, "rms_dbfs": -41.6379508972168}, {"start_seconds": 69.318, "end_seconds": 69.734, "rms_dbfs": -37.4284782409668}, {"start_seconds": 90.3, "end_seconds": 90.374, "rms_dbfs": -42.490806579589844}]

## qwen
Runtime 1.13.8; model load 5.979s; process peakRSS 3282.5MiB.
### emoji
{"runs": 3, "first_asr_rtf": 0.30540046970867285, "warm_asr_rtf_median": 0.2840671768208136, "cer": null, "first_segment_decode_s": 1.178818565000256, "first_visible_estimate_warm_s": 6.458818565000256, "first_visible_estimate_cold_s": 12.437731384000728, "max_output_tokens": 58, "first_segment_input_ready_s": 5.28, "same_text_across_repeats": true, "segments": 13, "timestamp_count": 0}

Full initial-pass raw output (VAD envelope, not word-aligned subtitle timing):

2.982–5.030: このイラストの意味わかりますか。
6.278–16.966: 今世界中で使われているのは何語でしょうか。英語、スペイン語、中国語。それはビジネス文書では使われない言葉です。
17.190–19.398: 正解はエモジ。
21.606–32.678: 絵文字はインターネット上のコミュニケーションで使われ、特に若い世代に人気です。絵文字の英語がハリウッドで作られるほど。
33.638–36.710: 絵文字は日本発祥。
36.966–41.670: 絵を文字のように使う新しい表現スタイルです。
42.406–49.734: 有名なものといえば、スマイル、コマリガオ、にぎりこぶし、ハートなど。
50.086–53.670: イミシナピザやナスも。
55.750–70.534: 絵文字が若者に使われる理由は驚きや喜びなどの感情を長い文章よりシンプルに伝えられるから。絵文字は昔から使われてきました。古代エジプトでも、元始時代でも。
71.718–74.598: 絵は言葉の壁を越えます。
75.590–78.726: 瞬時に脳が認識できるのです。
79.526–96.102: 例えば、園の中に斜め線、そして犬のマークがあれば、犬は禁止だとわかります。あるいはフロッピーディスクのアイコンを見れば、文書の保存だと理解できます。実物を知らなくても大丈夫。
97.286–110.406: 絵文字の影響で増えるのは生け物か人生の楽しみかはわかりませんが、いずれにせよオクスフォード自著が選んだ2015年の言葉は「歴史なき」。そう絵文字なんです。

Excluded VAD gaps: [{"start_seconds": 0.0, "end_seconds": 2.982, "rms_dbfs": -31.899211883544922}, {"start_seconds": 5.03, "end_seconds": 6.278, "rms_dbfs": -29.471839904785156}, {"start_seconds": 16.966, "end_seconds": 17.19, "rms_dbfs": -32.54572677612305}, {"start_seconds": 19.398, "end_seconds": 21.606, "rms_dbfs": -29.710044860839844}, {"start_seconds": 32.678, "end_seconds": 33.638, "rms_dbfs": -32.795570373535156}, {"start_seconds": 36.71, "end_seconds": 36.966, "rms_dbfs": -31.472190856933594}, {"start_seconds": 41.67, "end_seconds": 42.406, "rms_dbfs": -34.483009338378906}, {"start_seconds": 49.734, "end_seconds": 50.086, "rms_dbfs": -31.546646118164062}, {"start_seconds": 53.67, "end_seconds": 55.75, "rms_dbfs": -29.464710235595703}, {"start_seconds": 70.534, "end_seconds": 71.718, "rms_dbfs": -31.567626953125}, {"start_seconds": 74.598, "end_seconds": 75.59, "rms_dbfs": -27.271326065063477}, {"start_seconds": 78.726, "end_seconds": 79.526, "rms_dbfs": -32.05398941040039}, {"start_seconds": 96.102, "end_seconds": 97.286, "rms_dbfs": -33.863338470458984}, {"start_seconds": 110.406, "end_seconds": 113.96, "rms_dbfs": -32.50840759277344}]

### kishida
{"runs": 3, "first_asr_rtf": 0.3027257736397181, "warm_asr_rtf_median": 0.28668535565584546, "cer": null, "first_segment_decode_s": 1.1584504610000295, "first_visible_estimate_warm_s": 6.2464504610000295, "first_visible_estimate_cold_s": 12.2253632800005, "max_output_tokens": 74, "first_segment_input_ready_s": 5.088, "same_text_across_repeats": true, "segments": 12, "timestamp_count": 0}

Full initial-pass raw output (VAD envelope, not word-aligned subtitle timing):

1.286–4.838: はい、あのまず昨日の事件については。
5.222–6.726: ええ。
7.270–23.270: 容疑者が逮捕され、そして検察にいいて捜査中です。また私のほうからあの裁判のご心配をかけ、そしてご迷惑をかけした地元の皆さん方。
23.782–46.428: で漁業組合の方々ですとか、あるいは漁業者過去に受けた方に対し、直接お礼の電話を出させていただきました。あのそのほかにも国国内また国外からのさまざまなご心配やお見前の声をいただいたこと。
46.502–67.612: えをあの感謝申し上げております。えそして、まあ民衆議会の根幹をなす選挙において、えこうした暴力的なあ行為があ行われた、あそのことは絶対許すことはできないと考えています。あのそして、まあ大切なことは。
67.686–69.318: この。
69.734–90.300: 大切な選挙を最後まででやり通すことであると思っています。有権者の皆さんの声をしっかり選挙を通じて示してもらうこと。これは我が国にとってもまた我が国の民主主義にとってもほら大切なことであります。
90.374–114.044: えそういった思いで、え国民の皆さんとともに、この大切な選挙を最後までやり通していきたいと考えています。あのそして、その各党のこうした選挙運動が、あさまざまなられることがないように、えまあ警察においては、この警備いい安全について、万全を期してもらいたいと考えています。
114.630–123.046: 総理、朝信部で、あのこれから広島サミットもありますけれども、警察の昨日の警備体制は十分だったという風にお考えでしょうか。
123.942–144.476: いやあのケイビーについてはあ、今後その今回の事件の捜査が進むと同時にまあ検証も行われるものだと思っています。まあいずれにせよあのサミットをはじめ、世界各国から要人が集まる。
144.550–169.276: こうした日程においては、ああ日本全体として最大限警備安全に努めていかなければならないと思っています。こので、ひこみんな皆さんと共にこのこうした外交日程においても安全を確保できるよう。
169.350–173.926: お、力を尽くしていきたいと思っている。はい、わかります。

Excluded VAD gaps: [{"start_seconds": 0.0, "end_seconds": 1.286, "rms_dbfs": -58.10186767578125}, {"start_seconds": 4.838, "end_seconds": 5.222, "rms_dbfs": -32.40620422363281}, {"start_seconds": 6.726, "end_seconds": 7.27, "rms_dbfs": -43.871681213378906}, {"start_seconds": 23.27, "end_seconds": 23.782, "rms_dbfs": -25.745601654052734}, {"start_seconds": 46.428, "end_seconds": 46.502, "rms_dbfs": -44.607215881347656}, {"start_seconds": 67.612, "end_seconds": 67.686, "rms_dbfs": -41.6379508972168}, {"start_seconds": 69.318, "end_seconds": 69.734, "rms_dbfs": -37.4284782409668}, {"start_seconds": 90.3, "end_seconds": 90.374, "rms_dbfs": -42.490806579589844}, {"start_seconds": 114.044, "end_seconds": 114.63, "rms_dbfs": -42.085609436035156}, {"start_seconds": 123.046, "end_seconds": 123.942, "rms_dbfs": -41.09226608276367}, {"start_seconds": 144.476, "end_seconds": 144.55, "rms_dbfs": -43.280033111572266}, {"start_seconds": 169.276, "end_seconds": 169.35, "rms_dbfs": -40.84534454345703}, {"start_seconds": 173.926, "end_seconds": 175.7866875, "rms_dbfs": -63.02162551879883}]

### kishida114
{"runs": 3, "first_asr_rtf": 0.30300619628947617, "warm_asr_rtf_median": 0.29624345183771783, "cer": 0.19495412844036697, "first_segment_decode_s": 1.0795823620010196, "first_visible_estimate_warm_s": 6.16758236200102, "first_visible_estimate_cold_s": 12.146495181001491, "max_output_tokens": 65, "first_segment_input_ready_s": 5.088, "same_text_across_repeats": true, "segments": 8, "timestamp_count": 0}

Full initial-pass raw output (VAD envelope, not word-aligned subtitle timing):

1.286–4.838: はい、あのまず昨日の事件については。
5.222–6.726: ええ。
7.270–23.270: 容疑者が逮捕され、そして検察にいいて捜査中です。また私のほうからあの裁判のご心配をかけ、そしてご迷惑をかけした地元の皆さん方。
23.782–46.428: で漁業組合の方々ですとか、あるいは漁業者過去に受けた方に対し、直接お礼の電話を出させていただきました。あのそのほかにも国国内また国外からのさまざまなご心配やお見前の声をいただいたこと。
46.502–67.612: えをあの感謝申し上げております。えそして、まあ民衆議会の根幹をなす選挙において、えこうした暴力的なあ行為があ行われた、あそのことは絶対許すことはできないと考えています。あのそして、まあ大切なことは。
67.686–69.318: この。
69.734–90.300: 大切な選挙を最後まででやり通すことであると思っています。有権者の皆さんの声をしっかり選挙を通じて示してもらうこと。これは我が国にとってもまた我が国の民主主義にとってもほら大切なことであります。
90.374–114.000: そういった思いで、国民の皆さんとともに、この大切な選挙を最後までやり通していきたいと考えています。そして、その各党のこうした選挙運動が妨げられることがないように、警察においては警備、安全について万全を期してもらいたいと考えています。

Excluded VAD gaps: [{"start_seconds": 0.0, "end_seconds": 1.286, "rms_dbfs": -58.10186767578125}, {"start_seconds": 4.838, "end_seconds": 5.222, "rms_dbfs": -32.40620422363281}, {"start_seconds": 6.726, "end_seconds": 7.27, "rms_dbfs": -43.871681213378906}, {"start_seconds": 23.27, "end_seconds": 23.782, "rms_dbfs": -25.745601654052734}, {"start_seconds": 46.428, "end_seconds": 46.502, "rms_dbfs": -44.607215881347656}, {"start_seconds": 67.612, "end_seconds": 67.686, "rms_dbfs": -41.6379508972168}, {"start_seconds": 69.318, "end_seconds": 69.734, "rms_dbfs": -37.4284782409668}, {"start_seconds": 90.3, "end_seconds": 90.374, "rms_dbfs": -42.490806579589844}]

package com.hellfire.ass.news

import java.util.Locale

/**
 * Bilingual (English/Kiswahili) vocabulary for the news engine: stopwords, concept lexicon, gazetteer and entity aliases.
 * Concept keys are English lemmas so Kiswahili and English coverage of the same event share features.
 */
internal object NewsLexicon {
    val enStop = setOf("the", "a", "an", "of", "and", "or", "to", "in", "on", "at", "for", "by", "with", "from", "as", "is", "are", "was",
        "were", "be", "been", "being", "has", "have", "had", "it", "its", "this", "that", "these", "those", "their", "they", "them", "he", "she",
        "his", "her", "him", "we", "our", "you", "your", "i", "me", "my", "not", "no", "but", "if", "than", "then", "so", "such", "into", "over",
        "after", "before", "about", "against", "between", "during", "under", "up", "down", "out", "off", "also", "more", "most", "other", "some",
        "any", "all", "each", "both", "which", "who", "whom", "whose", "what", "when", "where", "why", "how", "there", "here", "will", "would",
        "can", "could", "should", "may", "might", "must", "do", "does", "did", "said", "says", "say", "told", "according", "while", "since",
        "until", "amid", "across", "around", "through", "per", "via", "new", "after", "last", "first", "one", "two", "least", "near", "on",
        "just", "very", "now", "still", "yet", "even", "only", "many", "much", "own", "same", "s", "mr", "mrs", "ms", "dr", "monday", "tuesday",
        "wednesday", "thursday", "friday", "saturday", "sunday", "today", "yesterday", "tomorrow", "week", "month", "year", "day", "time",
        "january", "february", "march", "april", "june", "july", "august", "september", "october", "november", "december", "jan", "feb",
        "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec", "people", "including", "added", "because", "however", "further")
    val swStop = setOf("na", "ya", "wa", "kwa", "katika", "ni", "la", "za", "cha", "vya", "kuwa", "hiyo", "huo", "hii", "huu", "hizo", "hayo",
        "hao", "wao", "yeye", "mimi", "sisi", "ninyi", "wewe", "pia", "lakini", "au", "ama", "kama", "kwamba", "ili", "hadi", "mpaka", "tangu",
        "baada", "kabla", "wakati", "sana", "zaidi", "kati", "juu", "chini", "ndani", "nje", "kwenye", "kwao", "wake", "yake", "zake", "lake",
        "chake", "vyake", "wetu", "yetu", "letu", "huku", "hapa", "pale", "kule", "hapo", "ambao", "ambayo", "ambaye", "ambazo", "ambalo",
        "ambacho", "kila", "wote", "yote", "zote", "baadhi", "bado", "tayari", "leo", "jana", "kesho", "mwaka", "siku", "saa", "wiki", "mwezi",
        "amesema", "alisema", "wamesema", "walisema", "ameongeza", "aliongeza", "alieleza", "ameeleza", "hata", "bila", "pamoja", "kutoka",
        "kwenda", "ndiyo", "hapana", "si", "siyo", "sio", "tu", "nini", "gani", "nani", "lini", "vipi", "je", "mmoja", "wengine", "wengi",
        "jumatatu", "jumanne", "jumatano", "alhamisi", "ijumaa", "jumamosi", "jumapili", "januari", "februari", "machi", "aprili", "mei",
        "juni", "julai", "agosti", "septemba", "oktoba", "novemba", "desemba", "huyo", "hili", "hilo", "kuhusu", "kufuatia", "kutokana",
        "kufuata", "mbalimbali", "kubwa", "ndogo", "mpya", "kwanza", "eneo", "maeneo", "hali", "ambapo", "ingawa", "hivyo", "vile", "hivi",
        "sasa", "tena", "nao", "naye", "kwake", "yao", "zao", "lao", "chao", "vyao", "wao", "watu")
    private val swMarkers = setOf("na", "ya", "wa", "kwa", "katika", "ni", "la", "za", "cha", "vya", "kuwa", "hiyo", "huo", "hii", "huu",
        "amesema", "alisema", "wamesema", "walisema", "serikali", "pia", "lakini", "kwamba", "ambao", "ambayo", "ambaye", "watu", "baada",
        "hadi", "wakati", "kutoka", "zaidi", "mkoa", "mkoani", "jijini", "mwaka", "leo", "jana", "wananchi", "kwenye", "huku", "hayo", "wake", "yake")
    private val enMarkers = setOf("the", "of", "and", "to", "in", "is", "was", "for", "on", "that", "with", "by", "at", "as", "from", "has",
        "have", "said", "were", "are", "after", "this", "it", "an", "be", "will", "their", "its", "which", "who", "been", "had")

    fun swScore(tokens: List<String>): Int = tokens.count { it in swMarkers }
    fun enScore(tokens: List<String>): Int = tokens.count { it in enMarkers }

    fun lower(s: String) = s.lowercase(Locale.ROOT)

    /** Light English stemmer; lexicon entries are stemmed the same way so inflections meet. */
    fun stem(word: String): String {
        var w = word
        if (w.length <= 3) return w
        if (w.endsWith("'s") || w.endsWith("’s")) w = w.dropLast(2)
        w = when {
            w.endsWith("ies") && w.length > 4 -> w.dropLast(3) + "y"
            w.endsWith("sses") -> w.dropLast(2)
            w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is") && w.length > 3 -> w.dropLast(1)
            else -> w
        }
        if (w.endsWith("ing") && w.length - 3 >= 3) w = undouble(w.dropLast(3))
        else if (w.endsWith("ed") && w.length - 2 >= 3) w = undouble(w.dropLast(2))
        if (w.endsWith("e") && w.length - 1 >= 3) w = w.dropLast(1)
        return w
    }
    private fun undouble(w: String): String =
        if (w.length >= 3 && w[w.length - 1] == w[w.length - 2] && w.last() !in "aeioulsz") w.dropLast(1) else w

    // --- Concept lexicon ---------------------------------------------------------------------------------------
    // concept | English forms (stemmed on load; phrases allowed) | Kiswahili forms (exact; "~root" = verb root with prefixes/extensions)
    private const val CONCEPTS = """
president|president,presidential,head of state,presidency|rais,urais,ikulu
government|government,govt,administration,cabinet|serikali,kiserikali,baraza la mawaziri
parliament|parliament,parliamentary,lawmaker,legislator,mp,mps,national assembly,legislature|bunge,bungeni,wabunge,mbunge
election|election,electoral,poll,polls,by-election,general election|uchaguzi,chaguzi,tume ya uchaguzi
vote|vote,voter,ballot,voting,turnout|kura,wapiga kura,kupiga kura,walipiga kura
candidate|candidate,nominee,aspirant|mgombea,wagombea
campaign|campaign,campaigning|kampeni
party|political party,opposition,ruling party,party|chama,vyama,upinzani,chama tawala,chama cha upinzani
result|results,outcome,winner|matokeo,mshindi
death|death,dead,die,died,dies,dying,kill,killed,killing,fatal,fatality,fatalities,death toll,lives lost,perish,bodies,passed away,lost their lives|kifo,vifo,~farik,maiti,miili,kupoteza maisha,wamepoteza maisha,walipoteza maisha,amepoteza maisha,aliyepoteza maisha,waliopoteza maisha,kuua,~uawa,yaua,yameua,imeua,umeua,wameua,waliua,aliua,ameua,kuwaua,yaliua,iliua,waliwaua,wamekufa,walikufa,amekufa,alikufa,kufa,waliokufa,aliyekufa,wamefariki
injured|injure,injured,injury,injuries,wounded,hurt|majeruhi,~jeruh
missing|missing,unaccounted|~potea,hawajulikani walipo
displaced|displaced,homeless,displacement|bila makazi,wamekosa makazi,kukosa makazi,hawana makazi,wamekosa mahali pa kuishi
evacuate|evacuate,evacuation,evacuee,relocate|~hamishw,kuhamisha,wamehamishwa
rescue|rescue,rescuer,rescued|uokoaji,waokoaji,~okolew,~okoa
flood|flood,flooding,floodwater,inundate,flash flood|mafuriko,furiko,~furik
rain|rain,rainfall,downpour,heavy rain,showers|mvua,mvua kubwa,masika,vuli
storm|storm,cyclone,hurricane,typhoon,strong winds|kimbunga,vimbunga,dhoruba,upepo mkali
earthquake|earthquake,quake,tremor,seismic|tetemeko,matetemeko,tetemeko la ardhi
landslide|landslide,mudslide|maporomoko,maporomoko ya udongo,~poromok
fire|fire,blaze,inferno,wildfire,burn,burnt,gutted|moto,kuungua,kuunguza,imeungua,iliungua,ziliungua,yaliungua,umeungua,uliungua,liliungua,imeteketea,kuteketea,iliteketea,ziliteketea,imeteketezwa,ziliteketezwa
drought|drought,dry spell|ukame
weather|weather,forecast,meteorological,temperature|hali ya hewa,utabiri,utabiri wa hali ya hewa
warning|warning,warn,alert,advisory,caution|tahadhari,onyo,~onya,~tahadharish
emergency|emergency,disaster,crisis,catastrophe|dharura,maafa,janga
lightning|lightning|radi
accident|accident,crash,collision,collide,overturn,capsize,derail,plunge|ajali,~gongan,~pinduk,kuzama,imezama,ilizama,walizama,wamezama
bus|bus,coach,minibus,daladala|basi,mabasi,daladala
truck|truck,lorry,tanker|lori,malori,roli
vehicle|car,vehicle,motorcycle,bodaboda,motorbike|gari,magari,pikipiki,bodaboda,bajaji
boat|boat,ferry,ship,vessel|boti,mashua,kivuko,meli,jahazi
train|train,railway,rail,sgr,standard gauge railway|treni,reli,garimoshi,reli ya kisasa
road|road,highway,motorway,expressway|barabara,njia kuu,barabara kuu
bridge|bridge,flyover,overpass,interchange|daraja,madaraja
port|port,harbour,harbor,dock|bandari
airport|airport,airstrip|uwanja wa ndege,viwanja vya ndege
flight|flight,airline,plane,aircraft,aviation|ndege,shirika la ndege,safari za ndege
traffic|traffic,congestion,gridlock|msongamano,foleni
police|police,policeman,officer,cop,constable|polisi,askari,jeshi la polisi,kamanda wa polisi
arrest|arrest,detain,custody,apprehend|~kamatw,~kamata,~shikiliw,mahabusu
suspect|suspect,accused|mtuhumiwa,watuhumiwa,washukiwa,mshukiwa
crime|crime,criminal,robbery,robber,theft,murder,homicide,gang,burglary|uhalifu,wahalifu,wizi,majambazi,jambazi,mauaji,ujambazi
fraud|fraud,scam,embezzle,embezzlement,money laundering,misappropriation|udanganyifu,utapeli,matapeli,ubadhirifu,utakatishaji fedha
corruption|corruption,bribe,bribery,graft,kickback|rushwa,ufisadi
drug|drug trafficking,narcotic,cocaine,heroin,cannabis|dawa za kulevya,bangi,mihadarati
court|court,tribunal,judiciary,high court,court of appeal|mahakama,mahakamani,mahakama kuu,mahakama ya rufani
judge|judge,magistrate,justice|jaji,majaji,hakimu,mahakimu
trial|trial,hearing,lawsuit,sue,petition|kesi,shauri,kushtaki,mashtaka
charge|charged with,charges against,face charges,faces charges,pressed charges,indict,indictment,prosecute,prosecution,prosecutor|~shtakiw,kufunguliwa mashtaka,mwendesha mashtaka
convict|convict,conviction,guilty,sentence,jail,prison,imprison|hatia,~hukumiw,kifungo,gerezani,jela,gereza
acquit|acquit,acquittal,cleared of|~achiliw,kuachiwa huru
verdict|verdict,ruling,judgment,judgement|hukumu,uamuzi wa mahakama
fine|fine,penalty,sanction|faini,adhabu,vikwazo
minister|minister,ministry,cabinet secretary|waziri,mawaziri,wizara,naibu waziri
prime_minister|prime minister,premier|waziri mkuu
finance|finance,treasury,fiscal|fedha,hazina,wizara ya fedha
appoint|appoint,appointment,nominate,nomination,named as|~teu,uteuzi,amemteua,alimteua
swear_in|sworn in,swear in,swearing-in,oath|~apishw,kiapo
resign|resign,resignation,step down,stepped down,quit|~jiuzulu,kung'atuka,amejiuzulu
dismiss|dismiss,sack,oust,remove from office,relieve of duties|~tumbuliw,~ondolew,~fukuzw,kutengua,ametengua,alitengua,~tenguliw
suspend|suspend,suspension|~simamish
approve|approve,approval,endorse,authorise,authorize,pass,passed,ratify|~idhinish,~pitish,idhini
reject|reject,rejection,refuse,turn down,turned down|kukataa,amekataa,wamekataa,limekataa,imekataa,walikataa,alikataa,~katali
sign|sign,signing,signature,ink|~sain,kutia saini,ametia saini,walitia saini
agreement|agreement,deal,accord,treaty,pact,memorandum,mou|makubaliano,mkataba,mikataba,mapatano,hati ya makubaliano
postpone|postpone,delay,defer,reschedule|~ahirish,kusogezwa mbele
cancel|cancel,cancellation,scrap,call off,called off|kufuta,imefutwa,amefuta,wamefuta,limefutwa,yamefutwa,kufutwa,~sitish
ban|ban,prohibit,prohibition,outlaw|marufuku,kupiga marufuku,imepiga marufuku
lift|lift,lifted|kuondoa marufuku,imeondoa marufuku
announce|announce,announcement,declare,declaration|~tangaz,tamko
launch|launch,unveil,introduce,debut,roll out,rollout,release|~zindu,uzinduzi
inaugurate|inaugurate,inauguration,open,opening,commission|~fungua,~funguliw,ufunguzi
close|close,closure,shut,shutdown|kufunga,imefungwa,zimefungwa,yamefungwa,umefungwa,kufungwa,kufungiwa
restore|restore,restoration,resume,resumption,reopen,reopening|~rejesh,~rejea,kufunguliwa tena
bank|bank,banking,lender|benki
central_bank|central bank,bank of tanzania,monetary policy committee,monetary policy|benki kuu,benki kuu ya tanzania,kamati ya sera ya fedha,sera ya fedha
interest_rate|interest rate,central bank rate,policy rate,lending rate,benchmark rate,cbr|riba,kiwango cha riba,riba ya benki kuu
rate|rate|kiwango
inflation|inflation,consumer prices,cpi|mfumuko wa bei,mfumko wa bei
price|price,cost,tariff,fare|bei,gharama,nauli
fuel|fuel,petrol,diesel,gasoline,kerosene,pump price|mafuta,petroli,dizeli,mafuta ya taa
tax|tax,taxation,levy,vat,duty,revenue|kodi,ushuru,mapato,tozo
budget|budget,spending,expenditure,appropriation|bajeti,makadirio
economy|economy,economic,gdp,growth|uchumi,kiuchumi,pato la taifa,ukuaji wa uchumi
currency|currency,shilling,dollar,exchange rate,forex|sarafu,shilingi,dola,fedha za kigeni,thamani ya shilingi
debt|debt,loan,borrowing,credit,bond|deni,madeni,mkopo,mikopo,hatifungani
investment|investment,investor,invest,fdi|uwekezaji,wawekezaji,~wekez
company|company,firm,corporation,enterprise,startup|kampuni,makampuni
market|stock,share price,stock exchange,equity,market|soko,masoko,hisa,soko la hisa
trade|trade,export,import|biashara,mauzo ya nje,uagizaji,wafanyabiashara
job|job,employment,unemployment,worker,employee,workforce|ajira,wafanyakazi,mfanyakazi
salary|salary,wage,pay rise|mshahara,mishahara
strike|strike,walkout,industrial action|mgomo,migomo,kugoma,wamegoma,waligoma
protest|protest,demonstration,demonstrator,rally,riot|maandamano,waandamanaji,~andaman
electricity|electricity,power,blackout,outage,grid,power cut|umeme,mgao wa umeme,gridi,kukatika kwa umeme
water|water,water supply,drinking water|maji,huduma ya maji
school|school,student,pupil,teacher,education,university,exam,examination|shule,wanafunzi,mwanafunzi,walimu,mwalimu,elimu,chuo,chuo kikuu,mtihani,mitihani
hospital|hospital,clinic,health facility|hospitali,zahanati,kituo cha afya
health|health,healthcare,medical,doctor,patient,nurse|afya,madaktari,daktari,mgonjwa,wauguzi,matibabu
disease|disease,illness,infection,virus,epidemic,pandemic|ugonjwa,magonjwa,maambukizi,virusi,homa
outbreak|outbreak|mlipuko,mlipuko wa ugonjwa
case|case|visa,wagonjwa
cholera|cholera|kipindupindu
malaria|malaria|malaria
ebola|ebola|ebola
marburg|marburg|marburg
mpox|mpox,monkeypox|homa ya nyani
hiv|hiv,aids|ukimwi,vvu
covid|covid,coronavirus|korona,uviko
vaccine|vaccine,vaccination,immunisation,immunization,jab|chanjo
food|food,hunger,famine,harvest,crop,maize,rice|chakula,njaa,mavuno,mazao,mahindi,mchele
agriculture|agriculture,farming,farmer|kilimo,wakulima,mkulima
phone|phone,smartphone,handset,mobile phone,cellphone,foldable|simu,simu janja,simu ya mkononi,rununu
ai|ai,artificial intelligence,chatbot,machine learning,llm,generative ai,language model|akili bandia,akili mnemba
chip|chip,processor,semiconductor,gpu,cpu,silicon,chipset|chipu,prosesa
software|software,app,application,operating system,os,firmware|programu,programu tumizi
internet|internet,broadband,online,network,connectivity|intaneti,mtandao,mitandao
cyber|cyberattack,cyber attack,hack,hacker,breach,ransomware,malware,phishing,vulnerability,data leak,cybersecurity,cyber security|udukuzi,wadukuzi,~dukuliw,mashambulizi ya mtandao
computer|computer,laptop,pc|kompyuta
space|space,satellite,rocket,orbit|anga za juu,setilaiti
research|research,study,scientist,discovery,researcher|utafiti,watafiti,wanasayansi,sayansi,ugunduzi
climate|climate,climate change,emission,carbon|tabianchi,mabadiliko ya tabianchi,hewa ukaa
team|team,squad,club|timu,klabu
match|match,game,fixture,derby|mechi,mchezo,michezo,pambano
goal|goal,scored,equaliser,equalizer|bao,mabao,goli,magoli
win|win,won,victory,beat,triumph,defeat,defeated|ushindi,~shind,kipigo
league|league,championship,tournament,cup|ligi,kombe,mashindano,michuano
player|player,striker,coach,footballer,athlete,midfielder,defender|mchezaji,wachezaji,kocha,mwanariadha
football|football,soccer|soka,mpira wa miguu,kandanda
entertainment|film,movie,music,song,album,concert,artist,singer,musician,celebrity,festival|filamu,muziki,wimbo,nyimbo,albamu,tamasha,msanii,wasanii,mwimbaji,bongo flava
award|award,prize|tuzo
war|war,troops,military,army,soldier,militant,rebel,insurgent,airstrike,shelling,fighting,clash,ceasefire|vita,wanajeshi,jeshi,waasi,mapigano,usitishaji mapigano
attack|attack,assault|shambulio,mashambulizi,~shambuli
terror|terror,terrorism,terrorist,bomb,explosion,blast|ugaidi,magaidi,bomu,mlipuko wa bomu
refugee|refugee,asylum,migrant|wakimbizi,mkimbizi
house|house,home,building,homestead|nyumba,majengo,jengo,makazi
collapse|collapse|~angu,kuporomoka
project|project,construction|mradi,miradi,ujenzi,kujenga,inajenga,~jengw
dam|dam,hydropower,reservoir|bwawa,mabwawa,bwawa la umeme
mining|mining,mine,miner,gold,mineral|madini,mgodi,migodi,wachimbaji,dhahabu
gas|natural gas,lng,gas|gesi,gesi asilia
tourism|tourism,tourist,wildlife,national park|utalii,watalii,wanyamapori,hifadhi
land|land,land dispute|ardhi,mgogoro wa ardhi
law|law,act,legislation,bill,amendment,regulation,constitution|sheria,muswada,miswada,marekebisho ya sheria,katiba,kanuni
policy|policy,directive,guideline,reform|sera,mwongozo,maagizo,~agiz,mageuzi
governor|governor|gavana
commissioner|regional commissioner,district commissioner,commissioner|mkuu wa mkoa,mkuu wa wilaya,kamishna
speaker|speaker|spika
official|official,authorities,authority,spokesperson,spokesman|mamlaka,maafisa,afisa,viongozi,kiongozi,msemaji
resident|resident,citizen,villager,public|wananchi,wakazi,raia,wanakijiji
report|report,statement|ripoti,taarifa
increase|increase,rise,raise,hike,surge,jump,climb|~ongez,ongezeko,kupanda,imepanda,zimepanda,yamepanda,ilipanda,zilipanda,inapanda
decrease|decrease,cut,reduce,reduction,fall,drop,decline,lower,slash|~pungu,punguzo,kushuka,imeshuka,ilishuka,zimeshuka,yameshuka
unchanged|unchanged,hold,held,maintain,keep|bila kubadilika,~baki
child|child,children,kid|watoto,mtoto
women|woman,women,girl|wanawake,mwanamke,wasichana
village|village,community|kijiji,vijiji,jamii
region|region,regional,district,province,county,ward|mkoa,mikoa,mkoani,wilaya,wilayani
city|city,town,capital,municipality,municipal|jiji,jijini,mji,mjini,manispaa,halmashauri
country|country,nation,national|nchi,taifa,kitaifa
lake|lake,river,ocean,sea,coast|ziwa,mto,mito,bahari
aid|aid,relief,donation,assistance,humanitarian|msaada,misaada,wahisani
compensation|compensation|fidia
investigation|investigation,probe,inquiry,investigate|uchunguzi,~chunguz,upelelezi
meeting|meeting,summit,conference,talks|mkutano,mikutano,kikao,mazungumzo
visit|visit,tour|ziara,~tembele
diplomacy|diplomatic,ambassador,embassy,envoy|balozi,ubalozi,mabalozi,kidiplomasia
celebration|celebration,anniversary,independence|sherehe,maadhimisho
religion|church,mosque,religious|kanisa,msikiti,dini
media|media,journalist,newspaper,press|vyombo vya habari,waandishi wa habari,mwandishi wa habari,gazeti
review|review,hands-on,benchmark|uhakiki
device|battery,camera,display,screen|betri,kamera,skrini
model|model|modeli
fishing|fishing,fisherman,fishermen|uvuvi,wavuvi
poaching|poaching,poacher|ujangili,majangili
heat|heatwave,heat wave|joto kali
recall|recall|kurejesha sokoni
"""

    class Lookup(val concept: String, val length: Int, val root: Boolean = false, val negative: Boolean = false)

    private val swPhrase = HashMap<String, String>()
    private val enPhrase = HashMap<String, String>()
    private val swRoots = ArrayList<Pair<String, String>>()
    private var maxLen = 1
    val conceptKeys = LinkedHashSet<String>()

    init {
        CONCEPTS.trim().lines().filter { it.isNotBlank() }.forEach { line ->
            val (concept, en, sw) = line.split('|').let { Triple(it[0].trim(), it.getOrElse(1) { "" }, it.getOrElse(2) { "" }) }
            conceptKeys += concept
            en.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { form ->
                val words = lower(form).split(Regex("[\\s-]+")).filter { it.isNotEmpty() }
                maxLen = maxOf(maxLen, words.size)
                enPhrase.putIfAbsent(words.joinToString(" ") { stem(it) }, concept)
                swPhrase.putIfAbsent("en:" + words.joinToString(" "), concept)
            }
            sw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { form ->
                if (form.startsWith("~")) swRoots += lower(form.drop(1)) to concept
                else {
                    val words = lower(form).split(Regex("\\s+"))
                    maxLen = maxOf(maxLen, words.size)
                    swPhrase.putIfAbsent(words.joinToString(" "), concept)
                }
            }
        }
    }

    private val swPrefix = Regex("^(?:ku|(?:ha|si)?(?:a|wa|ni|u|tu|m|mu|i|zi|li|ya|ki|vi|pa|hu|ku)?(?:li|me|na|ta|ki|nge|ngeli|ka|ja|mesha|sha)?(?:ye|o|cho|lo|yo|zo|vyo|po|ko|mo)?(?:m|mw|wa|ki|vi|i|zi|li|ya|u|ku|ji|ni|tu|ku)?)$")
    private val swSuffix = Regex("^[aeiouwlsyhzkn']{0,6}$")

    /** Concept starting at [i] (longest phrase first); also reports Kiswahili negative verb forms (ha-/si-). */
    fun conceptAt(tokens: List<String>, stems: List<String>, i: Int): Lookup? {
        for (len in minOf(maxLen, tokens.size - i) downTo 1) {
            val raw = tokens.subList(i, i + len).joinToString(" ")
            swPhrase[raw]?.let { return Lookup(it, len) }
            swPhrase["en:$raw"]?.let { return Lookup(it, len) }
            enPhrase[stems.subList(i, i + len).joinToString(" ")]?.let { return Lookup(it, len) }
        }
        val token = tokens[i]
        if (token.length >= 4) for ((root, concept) in swRoots) {
            val p = token.indexOf(root)
            if (p < 0) continue
            val prefix = token.substring(0, p)
            if (swPrefix.matches(prefix) && swSuffix.matches(token.substring(p + root.length)))
                return Lookup(concept, 1, root = true, negative = prefix.length > 2 && (prefix.startsWith("ha") || prefix.startsWith("si")))
        }
        return null
    }

    /** Down-weights concepts that appear in most news (IDF-like, static). */
    val commonness: Map<String, Double> = mapOf(
        "president" to 0.35, "government" to 0.35, "official" to 0.3, "resident" to 0.3, "country" to 0.3, "region" to 0.3, "city" to 0.3,
        "report" to 0.25, "minister" to 0.5, "police" to 0.6, "increase" to 0.5, "decrease" to 0.5, "unchanged" to 0.5, "company" to 0.5,
        "meeting" to 0.5, "child" to 0.6, "women" to 0.6, "rate" to 0.6, "price" to 0.7, "announce" to 0.35, "project" to 0.6, "health" to 0.7,
        "party" to 0.6, "law" to 0.7, "policy" to 0.6, "village" to 0.6, "visit" to 0.6, "media" to 0.4, "internet" to 0.7, "economy" to 0.7,
        "market" to 0.7, "bank" to 0.7, "finance" to 0.7, "house" to 0.7, "water" to 0.8, "people" to 0.2, "aid" to 0.7, "investigation" to 0.7,
        "inaugurate" to 0.8, "launch" to 0.8, "model" to 0.6, "device" to 0.7, "lake" to 0.7, "result" to 0.6, "job" to 0.7, "trade" to 0.7,
    )
    fun common(concept: String): Double = if (concept.startsWith("~")) 0.5 else commonness[concept] ?: 1.0

    /** Concept → event types it suggests, with strength. */
    val typeTriggers: Map<String, List<Pair<String, Double>>> = buildMap {
        fun t(type: String, w: Double, vararg cs: String) = cs.forEach { put(it, (get(it) ?: emptyList()) + (type to w)) }
        t("DISASTER", 1.0, "flood", "earthquake", "landslide", "drought", "collapse"); t("DISASTER", 0.8, "fire", "emergency")
        t("DISASTER", 0.4, "rain", "storm", "displaced", "evacuate", "rescue")
        t("WEATHER_ALERT", 1.0, "weather", "heat"); t("WEATHER_ALERT", 0.6, "storm", "rain", "lightning")
        t("ACCIDENT", 1.0, "accident"); t("ACCIDENT", 0.6, "bus", "truck", "vehicle", "boat", "flight", "train")
        t("PUBLIC_HEALTH", 1.0, "outbreak", "cholera", "ebola", "marburg", "mpox", "disease", "vaccine", "covid"); t("PUBLIC_HEALTH", 0.5, "hospital", "health", "malaria", "hiv", "case")
        t("CRIME", 1.0, "crime", "fraud", "drug", "corruption"); t("CRIME", 0.7, "arrest", "suspect"); t("CRIME", 0.3, "police")
        t("COURT", 1.0, "court", "judge", "trial", "verdict", "convict", "acquit", "charge"); t("COURT", 0.6, "fine")
        t("ELECTION", 1.0, "election", "vote", "candidate", "campaign"); t("ELECTION", 0.4, "party", "result")
        t("APPOINTMENT", 1.2, "appoint", "swear_in")
        t("RESIGNATION", 1.2, "resign"); t("RESIGNATION", 0.8, "dismiss")
        t("POLICY_DECISION", 0.8, "policy", "ban", "lift"); t("POLICY_DECISION", 0.5, "fine", "suspend", "approve", "tax"); t("POLICY_DECISION", 0.2, "government")
        t("LEGISLATION", 1.0, "law", "parliament"); t("LEGISLATION", 0.6, "budget", "speaker")
        t("ECONOMY_RATES", 1.2, "interest_rate", "inflation", "central_bank"); t("ECONOMY_RATES", 0.5, "currency")
        t("MARKETS", 1.0, "market"); t("MARKETS", 0.6, "currency", "fuel", "price")
        t("BUSINESS_DEAL", 1.0, "investment", "agreement"); t("BUSINESS_DEAL", 0.4, "company", "trade", "mining", "gas")
        t("PRODUCT_LAUNCH", 1.0, "launch"); t("PRODUCT_LAUNCH", 0.6, "phone", "chip", "model", "device"); t("PRODUCT_LAUNCH", 0.3, "software", "ai")
        t("PRODUCT_REVIEW", 1.4, "review")
        t("SECURITY_INCIDENT", 1.5, "cyber")
        t("CONFLICT", 1.0, "war", "terror", "refugee"); t("CONFLICT", 0.6, "attack")
        t("PROTEST", 1.0, "protest", "strike")
        t("INFRASTRUCTURE", 1.0, "bridge", "road", "dam", "port", "airport", "electricity"); t("INFRASTRUCTURE", 0.6, "project", "train", "inaugurate", "water")
        t("SPORTS_RESULT", 1.0, "match", "goal", "league", "football"); t("SPORTS_RESULT", 0.6, "team", "player", "win")
        t("SCIENCE", 1.0, "research", "space", "climate")
        t("ENTERTAINMENT", 1.0, "entertainment", "award")
    }
    /** Tie-break order (first wins) and the full type list. */
    val typePriority = listOf("DISASTER", "PUBLIC_HEALTH", "WEATHER_ALERT", "ACCIDENT", "SECURITY_INCIDENT", "CONFLICT", "ECONOMY_RATES",
        "ELECTION", "LEGISLATION", "COURT", "CRIME", "APPOINTMENT", "RESIGNATION", "POLICY_DECISION", "PROTEST", "INFRASTRUCTURE",
        "MARKETS", "BUSINESS_DEAL", "PRODUCT_REVIEW", "PRODUCT_LAUNCH", "SPORTS_RESULT", "SCIENCE", "ENTERTAINMENT", "OTHER")

    val compatibleTypes: Set<Set<String>> = listOf(
        "DISASTER" to "WEATHER_ALERT", "DISASTER" to "ACCIDENT", "DISASTER" to "PUBLIC_HEALTH", "CRIME" to "COURT", "POLICY_DECISION" to "LEGISLATION",
        "POLICY_DECISION" to "ECONOMY_RATES", "ECONOMY_RATES" to "MARKETS", "BUSINESS_DEAL" to "MARKETS", "PRODUCT_LAUNCH" to "PRODUCT_REVIEW",
        "CONFLICT" to "PROTEST", "CONFLICT" to "CRIME", "INFRASTRUCTURE" to "POLICY_DECISION", "APPOINTMENT" to "RESIGNATION",
        "POLICY_DECISION" to "COURT", "ELECTION" to "LEGISLATION", "ELECTION" to "POLICY_DECISION", "PROTEST" to "POLICY_DECISION",
        "SECURITY_INCIDENT" to "CRIME", "ACCIDENT" to "INFRASTRUCTURE", "BUSINESS_DEAL" to "POLICY_DECISION", "PUBLIC_HEALTH" to "POLICY_DECISION",
        "BUSINESS_DEAL" to "INFRASTRUCTURE", "PRODUCT_LAUNCH" to "BUSINESS_DEAL", "APPOINTMENT" to "POLICY_DECISION", "RESIGNATION" to "POLICY_DECISION",
        "CRIME" to "ACCIDENT", "DISASTER" to "INFRASTRUCTURE",
    ).map { setOf(it.first, it.second) }.toSet()

    val conceptTopics: Map<String, Set<String>> = buildMap {
        fun t(topics: Set<String>, vararg cs: String) = cs.forEach { put(it, (get(it) ?: emptySet()) + topics) }
        t(setOf("technology"), "phone", "ai", "chip", "software", "internet", "cyber", "computer", "device", "model")
        t(setOf("ai"), "ai"); t(setOf("smartphones"), "phone"); t(setOf("hardware"), "chip", "computer", "device"); t(setOf("software"), "software")
        t(setOf("security"), "cyber", "terror", "war", "attack"); t(setOf("business"), "company", "investment", "agreement", "market", "trade", "bank", "mining", "gas", "tourism")
        t(setOf("economy"), "inflation", "interest_rate", "central_bank", "budget", "tax", "economy", "currency", "debt", "fuel", "price", "salary", "job", "food")
        t(setOf("science"), "research", "space", "climate"); t(setOf("sports"), "team", "match", "goal", "league", "player", "football")
        t(setOf("entertainment"), "entertainment", "award"); t(setOf("health"), "hospital", "health", "disease", "outbreak", "cholera", "malaria", "ebola", "marburg", "mpox", "hiv", "covid", "vaccine")
        t(setOf("politics"), "president", "parliament", "election", "vote", "candidate", "campaign", "party", "minister", "prime_minister", "speaker")
        t(setOf("weather"), "weather", "rain", "storm", "heat", "lightning", "drought")
    }
    val typeTopics: Map<String, Set<String>> = mapOf(
        "ELECTION" to setOf("politics"), "LEGISLATION" to setOf("politics"), "APPOINTMENT" to setOf("politics"), "RESIGNATION" to setOf("politics"),
        "POLICY_DECISION" to setOf("politics"), "ECONOMY_RATES" to setOf("economy"), "MARKETS" to setOf("economy", "business"),
        "BUSINESS_DEAL" to setOf("business"), "PRODUCT_LAUNCH" to setOf("technology"), "PRODUCT_REVIEW" to setOf("technology"),
        "SECURITY_INCIDENT" to setOf("security", "technology"), "PUBLIC_HEALTH" to setOf("health"), "SPORTS_RESULT" to setOf("sports"),
        "SCIENCE" to setOf("science"), "ENTERTAINMENT" to setOf("entertainment"), "WEATHER_ALERT" to setOf("weather"), "CONFLICT" to setOf("security"),
    )

    // --- Gazetteer ---------------------------------------------------------------------------------------------
    class Place(val name: String, val region: String, val country: String?, val isCountry: Boolean)
    private val placeList = ArrayList<Place>()
    private val placeAlias = HashMap<String, String>()
    val places = HashMap<String, Place>()
    var maxPlaceLen = 1; private set
    /** Aliases that are ordinary words too: they must be capitalized and not open a sentence. */
    val ambiguousPlaces = setOf("mara", "moshi", "pwani", "same", "hai", "mafia", "magu", "bunda", "kusini", "chad", "niger", "jordan", "georgia", "turkey", "guinea", "dar")

    private fun place(region: String, country: String?, isCountry: Boolean, spec: String) {
        val names = spec.split('/').map { lower(it.trim()) }
        val p = Place(names[0], region, country, isCountry)
        placeList += p; places[p.name] = p
        names.forEach { alias -> placeAlias[alias] = p.name; maxPlaceLen = maxOf(maxPlaceLen, alias.split(' ').size) }
    }

    init {
        place("tanzania", null, true, "tanzania/tanzanian/tz/jamhuri ya muungano wa tanzania")
        listOf("dar es salaam/dar/daressalaam", "dodoma", "arusha", "mwanza", "zanzibar/unguja/zenji", "pemba", "mbeya", "morogoro", "tanga",
            "kilimanjaro", "moshi", "iringa", "kigoma", "tabora", "mtwara", "lindi", "ruvuma", "songea", "shinyanga", "kahama", "kagera",
            "bukoba", "mara", "musoma", "tarime", "singida", "rukwa", "sumbawanga", "katavi", "mpanda", "njombe", "makambako", "simiyu",
            "bariadi", "geita", "chato", "songwe", "tunduma", "manyara", "babati", "pwani", "kibaha", "bagamoyo", "chalinze", "rufiji",
            "kilwa", "ngorongoro", "serengeti", "kariakoo", "kinondoni", "ilala", "temeke", "ubungo", "kigamboni", "jangwani", "mbagala",
            "msimbazi", "tegeta", "kilosa", "ifakara", "handeni", "korogwe", "lushoto", "muheza", "pangani", "masasi", "newala", "nachingwea",
            "tunduru", "kasulu", "kibondo", "nzega", "igunga", "ukerewe", "sengerema", "misungwi", "kwimba", "karatu", "monduli", "longido",
            "mererani", "hanang", "katesh", "kondoa", "mbulu", "kiteto", "rombo", "mwanga", "same", "hai", "magu", "bunda", "mkuranga",
            "kisarawe", "kibiti", "ikwiriri", "mafinga", "kyela", "tukuyu", "mbarali", "chunya", "vwawa", "ludewa", "makete", "kilombero",
            "mvomero", "gairo", "kongwa", "mpwapwa", "bahi", "chamwino", "biharamulo", "ngara", "karagwe", "muleba", "misenyi", "maswa",
            "meatu", "busega", "itilima", "mkinga", "kilindi", "ruangwa", "liwale", "nanyumbu", "mbinga", "nyasa", "namtumbo",
            "lake victoria/ziwa victoria", "lake tanganyika/ziwa tanganyika", "lake nyasa/ziwa nyasa", "mount kilimanjaro/mlima kilimanjaro")
            .forEach { place("tanzania", "tanzania", false, it) }
        listOf("kenya", "uganda", "rwanda", "burundi", "democratic republic of congo/drc/dr congo/congo/kongo/jamhuri ya kidemokrasia ya kongo",
            "south sudan/sudan kusini", "ethiopia/uhabeshi", "somalia/somali", "mozambique/msumbiji", "malawi", "zambia", "eritrea", "djibouti",
            "comoros/komoro", "madagascar/madagaska")
            .forEach { place("east_africa", null, true, it) }
        mapOf("kenya" to "nairobi/mombasa/kisumu/nakuru/eldoret", "uganda" to "kampala/entebbe/gulu", "rwanda" to "kigali", "burundi" to "bujumbura/gitega",
            "democratic republic of congo" to "kinshasa/goma/bukavu/lubumbashi", "south sudan" to "juba", "ethiopia" to "addis ababa",
            "somalia" to "mogadishu", "mozambique" to "maputo/cabo delgado", "malawi" to "lilongwe/blantyre", "zambia" to "lusaka")
            .forEach { (country, cities) -> cities.split('/').forEach { place("east_africa", country, false, it) } }
        listOf("south africa/afrika kusini", "egypt/misri", "nigeria", "ghana", "sudan", "zimbabwe", "botswana", "namibia", "angola", "morocco/moroko",
            "algeria", "tunisia", "libya", "senegal", "cameroon/kameruni", "ivory coast/cote d'ivoire", "mali", "niger", "chad", "guinea")
            .forEach { place("africa", null, true, it) }
        mapOf("south africa" to "johannesburg/pretoria/cape town/durban", "egypt" to "cairo/kairo", "nigeria" to "lagos/abuja", "ghana" to "accra")
            .forEach { (country, cities) -> cities.split('/').forEach { place("africa", country, false, it) } }
        listOf("united states/marekani/usa/us/u.s./america", "united kingdom/uingereza/uk/britain/england", "china/uchina", "russia/urusi",
            "france/ufaransa", "germany/ujerumani", "japan/japani", "india/uhindi", "italy/italia", "spain/hispania", "canada", "brazil/brazili",
            "australia", "turkey/uturuki", "saudi arabia/saudia/saudi", "united arab emirates/uae/falme za kiarabu", "qatar", "iran", "iraq",
            "israel", "palestine/palestina", "gaza", "ukraine/ukraini", "pakistan", "indonesia", "south korea/korea kusini", "north korea/korea kaskazini",
            "netherlands/uholanzi", "belgium/ubelgiji", "switzerland/uswisi", "sweden/uswidi", "norway/norwe", "oman", "yemen", "mexico", "argentina")
            .forEach { place("global", null, true, it) }
        mapOf("united states" to "washington/new york/silicon valley/california/cupertino", "united kingdom" to "london", "china" to "beijing/shanghai/shenzhen",
            "russia" to "moscow", "france" to "paris", "germany" to "berlin", "japan" to "tokyo", "india" to "new delhi/mumbai", "belgium" to "brussels",
            "switzerland" to "geneva/davos", "united arab emirates" to "dubai/abu dhabi", "ukraine" to "kyiv", "south korea" to "seoul", "israel" to "jerusalem/tel aviv")
            .forEach { (country, cities) -> cities.split('/').forEach { place("global", country, false, it) } }
    }
    fun placeAlias(phrase: String): String? = placeAlias[phrase]

    // --- Entities ----------------------------------------------------------------------------------------------
    /** Alias → canonical entity. Aliases are matched on lowercase tokens; [exactCase] aliases must match the original text exactly. */
    class EntityAlias(val canonical: String, val exactCase: String?)
    private val entityAlias = HashMap<String, EntityAlias>()
    var maxEntityLen = 1; private set
    private fun entity(canonical: String, aliases: String, exact: String = "") {
        val c = lower(canonical)
        (listOf(canonical) + aliases.split('/').filter { it.isNotBlank() }).forEach { a ->
            val key = lower(a.trim()); entityAlias[key] = EntityAlias(c, null); maxEntityLen = maxOf(maxEntityLen, key.split(' ').size)
        }
        exact.split('/').filter { it.isNotBlank() }.forEach { a -> entityAlias[lower(a)] = EntityAlias(c, a) }
    }
    init {
        entity("Samia Suluhu Hassan", "Samia Suluhu/Rais Samia/President Samia/Dkt. Samia/Dk. Samia/Dr Samia/Samia")
        entity("Hussein Mwinyi", "Dkt. Mwinyi/Rais Mwinyi/President Mwinyi/Mwinyi")
        entity("Kassim Majaliwa", "Majaliwa"); entity("William Ruto", "Ruto"); entity("Yoweri Museveni", "Museveni")
        entity("Paul Kagame", "Kagame"); entity("Felix Tshisekedi", "Tshisekedi"); entity("Donald Trump", "Trump"); entity("Joe Biden", "Biden")
        entity("Xi Jinping", "Rais Xi"); entity("Vladimir Putin", "Putin"); entity("Volodymyr Zelensky", "Zelensky/Zelenskyy")
        entity("Elon Musk", "Musk"); entity("Tim Cook", ""); entity("Sam Altman", "Altman"); entity("Sundar Pichai", "Pichai"); entity("Jensen Huang", "")
        entity("Bank of Tanzania", "Benki Kuu ya Tanzania", "BoT/BOT"); entity("Tanzania Revenue Authority", "Mamlaka ya Mapato Tanzania", "TRA")
        entity("TANESCO", "Tanesco/Shirika la Umeme Tanzania/Tanzania Electric Supply Company"); entity("CCM", "Chama cha Mapinduzi")
        entity("CHADEMA", "Chadema"); entity("ACT-Wazalendo", "ACT Wazalendo"); entity("National Bureau of Statistics", "Ofisi ya Taifa ya Takwimu", "NBS")
        entity("World Health Organization", "Shirika la Afya Duniani", "WHO"); entity("International Monetary Fund", "Shirika la Fedha la Kimataifa", "IMF")
        entity("World Bank", "Benki ya Dunia"); entity("United Nations", "Umoja wa Mataifa", "UN"); entity("European Union", "Umoja wa Ulaya", "EU")
        entity("African Union", "Umoja wa Afrika", "AU"); entity("East African Community", "Jumuiya ya Afrika Mashariki", "EAC"); entity("SADC", "")
        entity("Tanzania Ports Authority", "Mamlaka ya Bandari Tanzania", "TPA"); entity("TANROADS", "Tanroads"); entity("Tanzania Railways Corporation", "Shirika la Reli Tanzania", "TRC")
        entity("Air Tanzania", "ATCL"); entity("DAWASA", "Dawasa"); entity("EWURA", "Ewura"); entity("TCRA", "")
        entity("Tanzania Meteorological Authority", "Mamlaka ya Hali ya Hewa Tanzania", "TMA"); entity("Independent National Electoral Commission", "Tume Huru ya Taifa ya Uchaguzi", "INEC/NEC")
        entity("PCCB", "TAKUKURU/Takukuru"); entity("Tanzania Police Force", "Jeshi la Polisi Tanzania/Jeshi la Polisi"); entity("TPDF", "JWTZ")
        entity("Vodacom", ""); entity("Airtel", ""); entity("Yas", "Tigo"); entity("Halotel", ""); entity("CRDB", ""); entity("NMB", ""); entity("NBC", "")
        entity("Simba SC", "Simba Sports Club/Wekundu wa Msimbazi"); entity("Young Africans", "Yanga/Yanga SC/Young Africans SC"); entity("Azam FC", "Azam")
        entity("Taifa Stars", ""); entity("FIFA", ""); entity("CAF", "")
        entity("OpenAI", "Open AI"); entity("Anthropic", ""); entity("Google", "Alphabet"); entity("Apple", ""); entity("Samsung", ""); entity("Nvidia", "NVIDIA")
        entity("AMD", "Advanced Micro Devices"); entity("Intel", ""); entity("Qualcomm", ""); entity("Microsoft", ""); entity("Meta", "Facebook")
        entity("Amazon", ""); entity("Tesla", ""); entity("Huawei", ""); entity("Xiaomi", ""); entity("Tecno", ""); entity("Infinix", ""); entity("Oppo", "OPPO")
        entity("TSMC", ""); entity("MediaTek", ""); entity("Sony", ""); entity("Netflix", ""); entity("Starlink", ""); entity("SpaceX", ""); entity("NASA", "")
        entity("ChatGPT", ""); entity("Gemini", ""); entity("Claude", ""); entity("iPhone", ""); entity("Galaxy", ""); entity("Pixel", "")
        entity("TikTok", ""); entity("X Corp", "Twitter"); entity("Arm", "")
    }
    fun entityAlias(phrase: String): EntityAlias? = entityAlias[phrase]

    /** Well-known entities that show up in many unrelated events: a shared one is weak evidence. */
    val famousEntities: Map<String, Double> = mapOf(
        "samia suluhu hassan" to 0.2, "hussein mwinyi" to 0.3, "kassim majaliwa" to 0.3, "william ruto" to 0.3, "yoweri museveni" to 0.3,
        "paul kagame" to 0.3, "donald trump" to 0.2, "joe biden" to 0.3, "xi jinping" to 0.3, "vladimir putin" to 0.3, "elon musk" to 0.3,
        "ccm" to 0.3, "chadema" to 0.4, "united nations" to 0.3, "european union" to 0.4, "african union" to 0.4, "east african community" to 0.4,
        "world health organization" to 0.5, "tanzania police force" to 0.4, "bank of tanzania" to 0.6, "tanzania revenue authority" to 0.5,
        "tanesco" to 0.6, "apple" to 0.35, "google" to 0.3, "microsoft" to 0.3, "samsung" to 0.4, "openai" to 0.4, "meta" to 0.35, "amazon" to 0.35,
        "nvidia" to 0.4, "intel" to 0.5, "amd" to 0.5, "qualcomm" to 0.5, "huawei" to 0.5, "iphone" to 0.5, "chatgpt" to 0.5, "fifa" to 0.4, "caf" to 0.4,
        "simba sc" to 0.5, "young africans" to 0.5, "world bank" to 0.5, "international monetary fund" to 0.5,
    )
    val entityTopics: Map<String, Set<String>> = buildMap {
        listOf("openai", "anthropic", "chatgpt", "gemini", "claude").forEach { put(it, setOf("ai", "technology")) }
        listOf("google", "apple", "samsung", "microsoft", "meta", "amazon", "huawei", "xiaomi", "tecno", "infinix", "oppo", "tiktok", "x corp",
            "netflix", "starlink", "spacex", "sony").forEach { put(it, setOf("technology")) }
        listOf("nvidia", "amd", "intel", "qualcomm", "tsmc", "mediatek", "arm").forEach { put(it, setOf("technology", "hardware")) }
        listOf("iphone", "galaxy", "pixel").forEach { put(it, setOf("technology", "smartphones")) }
        listOf("bank of tanzania", "international monetary fund", "world bank", "national bureau of statistics", "tanzania revenue authority").forEach { put(it, setOf("economy")) }
        listOf("simba sc", "young africans", "azam fc", "taifa stars", "fifa", "caf").forEach { put(it, setOf("sports")) }
        listOf("world health organization").forEach { put(it, setOf("health")) }
        listOf("ccm", "chadema", "act-wazalendo", "independent national electoral commission").forEach { put(it, setOf("politics")) }
        put("tanzania meteorological authority", setOf("weather")); put("nasa", setOf("science"))
    }
    /** National institutions whose involvement raises impact. */
    val nationalInstitutions = setOf("bank of tanzania", "tanzania revenue authority", "tanesco", "independent national electoral commission",
        "national bureau of statistics", "tanzania ports authority", "tanzania railways corporation", "tanzania meteorological authority",
        "tanzania police force", "tpdf", "pccb", "samia suluhu hassan", "hussein mwinyi", "kassim majaliwa", "dawasa", "ewura", "tcra")
    /** Courtesy titles and roles trimmed from capitalized names. */
    val titleWords = setOf("president", "rais", "prime", "minister", "waziri", "mkuu", "dr", "dkt", "dk", "mr", "mrs", "ms", "bw", "bi", "mheshimiwa",
        "mhe", "judge", "jaji", "chief", "governor", "gavana", "spika", "speaker", "mkurugenzi", "director", "ceo", "inspector", "general", "sheikh",
        "bishop", "askofu", "kamanda", "commander", "regional", "commissioner", "deputy", "naibu", "katibu", "secretary", "professor", "prof",
        "hon", "sir", "the", "mkoa", "wa", "says", "said", "amesema", "alisema", "msemaji", "spokesperson", "police", "polisi", "former")
    /** Capitalized words that are never entity acronyms. */
    val capsNoise = setOf("BREAKING", "UPDATE", "LIVE", "WATCH", "VIDEO", "PHOTOS", "TSH", "TZS", "USD", "KSH", "AM", "PM", "TV", "CEO", "EAT",
        "GMT", "UTC", "OK", "AI", "MP", "MPS", "SW", "EN", "PHOTO", "PICHA", "HABARI", "NEWS", "OPINION", "MAONI", "DHARURA", "URGENT", "SGR", "CBR", "GDP", "CPI", "VAT", "LNG")
}

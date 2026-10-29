package ai.learnharness.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.learnharness.android.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent { HarnessTheme { HarnessApp() } }
    }
}

@Composable fun HarnessTheme(content: @Composable () -> Unit) {
    val palette=darkColorScheme(primary=Color(0xFFE3CA80),onPrimary=Color(0xFF201D14),secondary=Color(0xFFB8B8B1),
        background=Color(0xFF141414),surface=Color(0xFF181818),surfaceVariant=Color(0xFF222222),secondaryContainer=Color(0xFF252525),
        onSurface=Color(0xFFE1E1DC),onBackground=Color(0xFFE1E1DC),outline=Color(0xFF44443E),outlineVariant=Color(0xFF30302C))
    val base=Typography()
    MaterialTheme(colorScheme=palette, typography=base.copy(
        displaySmall=base.displaySmall.copy(fontFamily=FontFamily.Serif),headlineLarge=base.headlineLarge.copy(fontFamily=FontFamily.Serif),
        headlineMedium=base.headlineMedium.copy(fontFamily=FontFamily.Serif),headlineSmall=base.headlineSmall.copy(fontFamily=FontFamily.Serif),
        labelSmall=base.labelSmall.copy(fontFamily=FontFamily.Monospace),bodyLarge=base.bodyLarge.copy(fontSize=17.sp,lineHeight=27.sp)), content=content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun HarnessApp(vm: LearnViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var create by rememberSaveable { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val drawer=rememberDrawerState(DrawerValue.Closed)
    val scope=rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle,vm) {
        val observer=LifecycleEventObserver {_,_->vm.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))}
        lifecycle.addObserver(observer)
        vm.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {lifecycle.removeObserver(observer);vm.setForeground(false)}
    }
    DisposableEffect(lifecycle,state.pcMode,state.paired) {
        var job: kotlinx.coroutines.Job? = null
        fun start() { if(job == null) job = vm.poll() }
        val observer = LifecycleEventObserver { _, event ->
            if(event == Lifecycle.Event.ON_RESUME) start()
            if(event == Lifecycle.Event.ON_PAUSE) { job?.cancel(); job=null }
        }
        lifecycle.addObserver(observer)
        if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()
        onDispose { job?.cancel(); lifecycle.removeObserver(observer) }
    }
    BackHandler(state.selectedId != null) { vm.open(null) }
    val local = state.data.sessions.firstOrNull { it.id == state.selectedId }
    val remote = state.remote?.takeIf { it.id == state.selectedId }
    val title = if(state.selectedId != null) (if(state.pcMode) remote?.title else state.native?.title?:local?.title) ?: "Loading session"
        else listOf("LearnHarness","Knowledge","Practice","Settings")[tab]
    ModalNavigationDrawer(drawerState=drawer,drawerContent={ModalDrawerSheet(Modifier.width(310.dp)) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("LEARNHARNESS",fontFamily=FontFamily.Monospace,letterSpacing=2.sp,style=MaterialTheme.typography.labelLarge)
            Text("A place to understand.",fontFamily=FontFamily.Serif,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick={vm.open(null);tab=0;create=true;scope.launch {drawer.close()}},modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.Add,null);Text("New session")}
        }
        listOf("Sessions","Knowledge graph","Practice","Settings").forEachIndexed {i,label->
            NavigationDrawerItem(label={Text(label)},selected=tab==i&&state.selectedId==null,onClick={vm.open(null);tab=i;scope.launch {drawer.close()}},modifier=Modifier.padding(horizontal=12.dp))
        }
        HorizontalDivider(Modifier.padding(vertical=14.dp))
        Text("RECENT SESSIONS",Modifier.padding(horizontal=24.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(12.dp)) {
            items(state.shared.entries.sortedByDescending {it.record["updatedAt"].toString()}.take(30),key={it.view.id}){entry->
                NavigationDrawerItem(label={Text(entry.view.title,maxLines=2)},selected=state.selectedId==entry.view.id,onClick={vm.switchSource(false);vm.open(entry.view.id);scope.launch{drawer.close()}})
            }
        }
        Text(state.syncStatus,Modifier.padding(24.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }}) {
    Scaffold(
        topBar = { TopAppBar(title={ Text(title,maxLines=1,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold) },
            navigationIcon={IconButton(onClick={scope.launch {drawer.open()}}){Icon(Icons.Outlined.Menu,"Open sessions")}},
            actions={
                if(state.pcMode && state.paired) IconButton(onClick=vm::refresh,enabled=!state.busy) { Icon(Icons.Outlined.Refresh,"Refresh PC") }
                if(state.syncConfigured&&!state.pcMode)IconButton(onClick=vm::sync,enabled=!state.busy){Icon(Icons.Outlined.CloudSync,"Sync lessons")}
                if(state.selectedId != null && !state.pcMode&&state.native==null) IconButton(onClick={delete=true},enabled=!state.busy) { Icon(Icons.Outlined.DeleteOutline,"Delete session") }
            }) },
        bottomBar={ if(state.selectedId == null) NavigationBar {
            val icons = listOf(Icons.AutoMirrored.Outlined.MenuBook,Icons.Outlined.Hub,Icons.Outlined.Psychology,Icons.Outlined.Settings)
            listOf("Learn","Knowledge","Practice","Settings").forEachIndexed { index,label ->
                NavigationBarItem(selected=tab==index,onClick={tab=index},icon={Icon(icons[index],label)},label={Text(label)})
            }
        } }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Banner(it,true,vm::dismissError) }
            state.notice?.let { Banner(it,false,vm::dismissError) }
            if(!state.loaded) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) { CircularProgressIndicator() }
            else if(state.selectedId != null) {
                if(state.pcMode && remote != null) RemoteLesson(remote,state,vm)
                else if(!state.pcMode && state.native!=null) RemoteLesson(state.native!!,state,vm)
                else if(!state.pcMode && local != null) LocalLesson(local,state,vm)
                else Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) { Text("Refresh to load this session.") }
            } else when(tab) {
                0 -> Library(state,vm,{create=true},{tab=3})
                1 -> Knowledge(state,vm)
                2 -> Practice(state,{create=true},{tab=0})
                else -> Settings(state,vm)
            }
        }
    }
    }
    if(create) {
        var goal by rememberSaveable { mutableStateOf("") }
        AlertDialog(onDismissRequest={create=false},title={Text(if(tab==2) "Start a practice session" else "What do you want to understand?")},
            text={ Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(if(state.pcMode) "This session will appear in your PC app." else "Start with a question. Build understanding one step at a time.")
                OutlinedTextField(value=goal,onValueChange={if(it.length<=500)goal=it},label={Text("Your learning goal")},minLines=3,modifier=Modifier.fillMaxWidth())
            } },confirmButton={TextButton(onClick={create=false;vm.start(goal,tab==2)},enabled=goal.isNotBlank()&&!state.busy) {Text("Start")}},
            dismissButton={TextButton(onClick={create=false}) {Text("Cancel")}})
    }
    if(delete) AlertDialog(onDismissRequest={delete=false},title={Text("Delete this local session?")},text={Text("Its local messages and knowledge entries will be removed. Export a backup first if you want to keep them.")},
        confirmButton={TextButton(onClick={delete=false;state.selectedId?.let(vm::delete)}) {Text("Delete")}},dismissButton={TextButton(onClick={delete=false}) {Text("Cancel")}})
}

@Composable private fun Banner(text: String, error: Boolean, dismiss: () -> Unit) {
    Surface(color=if(error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(start=16.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(text,Modifier.weight(1f).padding(vertical=10.dp),style=MaterialTheme.typography.bodySmall)
            IconButton(onClick=dismiss) {Icon(Icons.Outlined.Close,"Dismiss")}
        }
    }
}
@Composable private fun SourceSwitch(state: AppState,vm: LearnViewModel) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        FilterChip(selected=!state.pcMode,onClick={vm.switchSource(false)},label={Text("On this phone")},leadingIcon={Icon(Icons.Outlined.Smartphone,null,Modifier.size(16.dp))})
        FilterChip(selected=state.pcMode,onClick={vm.switchSource(true)},label={Text("My PC")},leadingIcon={Icon(Icons.Outlined.Computer,null,Modifier.size(16.dp))})
    }
    if(state.pcMode) Text(if(!state.paired) "Pair your PC in Settings to get started." else if(state.online) "Connected · changes are shared with your PC" else "PC offline · showing the last available session",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
}
@Composable private fun Library(state:AppState,vm:LearnViewModel,new:()->Unit,settings:()->Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("library"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item { Text(if(state.pcMode)"Live PC connection" else state.syncStatus,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Surface(shape=RoundedCornerShape(4.dp),color=MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Text("LEARNING, ONE REASONING STEP AT A TIME",color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=10.sp,letterSpacing=1.sp,fontFamily=FontFamily.Monospace)
                Text("What do you want\nto understand?",style=MaterialTheme.typography.displaySmall)
                Text("Start with a question. Find your starting point, follow the reasoning, and build a map of what you learn.",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyLarge)
                Button(onClick=new,enabled=!state.busy&&(!state.pcMode||state.paired),shape=RoundedCornerShape(6.dp)) {Icon(Icons.Outlined.Add,null);Spacer(Modifier.width(8.dp));Text("New learning session")}
            }
        } }
        if((!state.pcMode&&!state.chatGptConnected)||(state.pcMode&&!state.paired)) item { OutlinedCard(onClick=settings,modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {Text(if(state.pcMode) "Connect your PC" else "Continue with ChatGPT",fontWeight=FontWeight.SemiBold);Text(if(state.pcMode) "Open your live desktop connection." else "Use your subscription on this phone. Your PC can stay off.",style=MaterialTheme.typography.bodySmall)}
        } }
        item { Text("Your sessions",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold) }
        if(state.pcMode) items(state.remoteSessions,key={it.id}) {s -> SessionCard(s.title,if(s.busy) "Working on PC" else if(s.mode=="practice") "Practice · PC" else "Learning · PC") {vm.open(s.id)} }
        else {
            items(state.shared.entries.sortedByDescending {it.record["updatedAt"].toString()},key={it.view.id}){e->SessionCard(e.view.title,"${if(e.view.mode=="practice")"Practice" else "Learning"} · ${e.view.messages.size} messages"){vm.open(e.view.id)}}
            items(state.data.sessions.filterNot {it.id in state.shared.migratedLegacyIds}.sortedByDescending { it.createdAt },key={it.id}) {s -> SessionCard(s.title,if(s.sample) "Sample lesson · available offline" else "Earlier phone lesson · ${s.messages.size} messages") {vm.open(s.id)} }
        }
        if(!state.pcMode && state.data.sessions.isEmpty()&&state.shared.entries.isEmpty()) item { Text("Your next question is a good place to begin.",color=MaterialTheme.colorScheme.onSurfaceVariant);TextButton(onClick=vm::sample) {Text("Explore the orbit sample")}}
        if(state.pcMode&&state.paired&&state.remoteSessions.isEmpty()) item {Text("Your PC sessions will appear here when the connection is available.")}
    }
}
@Composable private fun SessionCard(title:String,subtitle:String,open:()->Unit) {
    OutlinedCard(onClick=open,modifier=Modifier.fillMaxWidth()) { Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Outlined.MenuBook,null,tint=MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(horizontal=14.dp)) {Text(title,fontWeight=FontWeight.Medium);Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        Icon(Icons.AutoMirrored.Outlined.ArrowForward,null,Modifier.size(18.dp))
    } }
}
@Composable private fun ColumnScope.LocalLesson(session: Session,state:AppState,vm:LearnViewModel) {
    var showPlan by rememberSaveable(session.id) { mutableStateOf(false) }
    Row(Modifier.padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        SuggestionChip(onClick={showPlan=false},label={Text(session.phase.replaceFirstChar { it.uppercase() })})
        FilterChip(selected=showPlan,onClick={showPlan=!showPlan},label={Text("Learning plan (${session.plan.size})")})
    }
    if(showPlan) Plan(session.plan) else Transcript(session.messages,Modifier.weight(1f)) {
        if(session.sample) Text("Offline sample. A provider key is needed to continue with AI.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
        session.messages.lastOrNull()?.turn?.question?.takeIf { it.isNotBlank() }?.let {QuestionHeading(it)}
        if(session.messages.lastOrNull()?.role=="user") Button(onClick=vm::retry,enabled=!state.busy) {Text("Get tutor response")}
        else Composer(session.id,enabled=!state.busy,onSend=vm::send)
    }
    if(state.busy) TextButton(onClick=vm::cancel,modifier=Modifier.padding(horizontal=12.dp)) {Text("Stop waiting")}
}
@Composable private fun ColumnScope.RemoteLesson(session: RemoteSession,state:AppState,vm:LearnViewModel) {
    var plan by rememberSaveable(session.id) { mutableStateOf(false) }
    val available=!state.pcMode||state.online
    Row(Modifier.padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(if(!state.pcMode)state.syncStatus else if(state.online) "Live PC session" else "Offline · cached copy",Modifier.weight(1f).padding(top=14.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        FilterChip(selected=plan,onClick={plan=!plan},label={Text("Plan (${session.plan.size})")})
    }
    if(plan) Plan(session.plan) else Transcript(session.messages,Modifier.weight(1f)) {
        session.error?.let {Text(it,color=MaterialTheme.colorScheme.error);Button(onClick=vm::retry,enabled=!state.busy&&available) {Text("Retry response")}}
        if(session.busy) {Text("Your PC is thinking… This session refreshes automatically.");LinearProgressIndicator(Modifier.fillMaxWidth())}
        else if(session.question != null) QuestionForm(session.question,!state.busy&&available,vm)
        else {
            session.problem?.let {QuestionHeading(it.statement)}
            if(session.needsContinuation) Button(onClick={vm.pcAction("continue")},enabled=!state.busy&&available) {Text("Continue lesson")}
            if(session.mode=="practice"&&session.problem==null) OutlinedButton(onClick={vm.pcAction("next")},enabled=!state.busy&&available) {Text("Next problem")}
            Composer(session.id,enabled=!state.busy&&available,onSend=vm::send)
        }
    }
    if(state.busy) TextButton(onClick=vm::cancel) {Text("Stop waiting")}
}
@Composable private fun Transcript(messages:List<Message>,modifier:Modifier,footer:@Composable ()->Unit) {
    val list = rememberLazyListState()
    LaunchedEffect(messages.size) { if(messages.isNotEmpty()) list.scrollToItem(messages.size-1) }
    LazyColumn(modifier.fillMaxWidth(),state=list,contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        items(messages,key={it.id}) {m ->
            Surface(shape=RoundedCornerShape(6.dp),color=if(m.role=="user") MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.background,modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(if(m.role=="user") "YOU" else "LEARNHARNESS",style=MaterialTheme.typography.labelSmall,letterSpacing=1.sp,color=MaterialTheme.colorScheme.primary)
                    RichText(m.text,Modifier.fillMaxWidth())
                    if(m.thinking.isNotBlank()) Text("Your thinking: ${m.thinking}",style=MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {Column(verticalArrangement=Arrangement.spacedBy(12.dp),content={footer()})}
    }
}
@Composable private fun QuestionHeading(text:String) {RichText(text,Modifier.fillMaxWidth())}
@Composable private fun Composer(id:String,enabled:Boolean,onSend:(String,String,Int)->Unit) {
    var answer by rememberSaveable(id) {mutableStateOf("")}
    var thinking by rememberSaveable(id) {mutableStateOf("")}
    var confidence by rememberSaveable(id) {mutableFloatStateOf(3f)}
    OutlinedTextField(value=answer,onValueChange={if(it.length<=12000)answer=it},label={Text("Your answer or question")},minLines=2,modifier=Modifier.fillMaxWidth(),enabled=enabled)
    OutlinedTextField(value=thinking,onValueChange={if(it.length<=12000)thinking=it},label={Text("Your thinking (optional)")},modifier=Modifier.fillMaxWidth(),enabled=enabled)
    Text("Confidence: ${confidence.toInt()}/5",style=MaterialTheme.typography.labelMedium)
    Slider(value=confidence,onValueChange={confidence=it},valueRange=1f..5f,steps=3,enabled=enabled)
    Button(onClick={onSend(answer,thinking,confidence.toInt());answer="";thinking=""},enabled=enabled&&answer.isNotBlank(),modifier=Modifier.fillMaxWidth()) {Text("Send")}
}
@Composable private fun QuestionForm(q:RemoteQuestion,enabled:Boolean,vm:LearnViewModel) {
    var selected by rememberSaveable(q.id) {mutableStateOf(listOf<String>())}
    var text by rememberSaveable(q.id) {mutableStateOf("")}
    var thinking by rememberSaveable(q.id) {mutableStateOf("")}
    var confidence by rememberSaveable(q.id) {mutableFloatStateOf(50f)}
    QuestionHeading(q.question)
    q.context?.let {Text(it)}
    q.options.forEach {option ->
        OutlinedCard(onClick={if(enabled) selected=if(q.mode=="single-select") listOf(option.value) else if(option.value in selected) selected-option.value else selected+option.value},modifier=Modifier.fillMaxWidth()) {
            Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically) {
                if(q.mode=="multi-select") Checkbox(checked=option.value in selected,onCheckedChange=null,enabled=enabled)
                else RadioButton(selected=option.value in selected,onClick=null,enabled=enabled)
                Column(Modifier.padding(start=10.dp).weight(1f)) {RichText(option.label);if(option.description.isNotBlank())Text(option.description,style=MaterialTheme.typography.bodySmall)}
            }
        }
    }
    if(q.mode=="text"||q.kind=="ask") OutlinedTextField(value=text,onValueChange={if(it.length<=12000)text=it},label={Text(if(q.mode=="text") "Your answer" else "Other answer")},modifier=Modifier.fillMaxWidth(),enabled=enabled)
    OutlinedTextField(value=thinking,onValueChange={if(it.length<=12000)thinking=it},label={Text("Explain your thinking")},modifier=Modifier.fillMaxWidth(),enabled=enabled)
    if(q.kind=="quiz") {Text("Confidence: ${confidence.toInt()}%",style=MaterialTheme.typography.labelMedium);Slider(value=confidence,onValueChange={confidence=it},valueRange=0f..100f,enabled=enabled)}
    Button(onClick={vm.answer(q,selected,text,thinking,confidence.toInt(),false)},enabled=enabled&&(selected.isNotEmpty()||text.isNotBlank()),modifier=Modifier.fillMaxWidth()) {Text("Submit answer")}
    if(q.kind!="ask") TextButton(onClick={vm.answer(q,emptyList(),"",thinking,0,true)},enabled=enabled) {Text("I don't know yet")}
}
@Composable private fun Plan(nodes:List<PlanNode>) {
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(nodes.isEmpty()) item {Text("Your tutor will build a plan after finding your starting point.")}
        items(nodes,key={it.id}) {n -> OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(n.title,style=MaterialTheme.typography.titleMedium);Text(n.summary)
            if(n.prerequisites.isNotEmpty()) Text("Builds on: "+n.prerequisites.joinToString {id->nodes.find{it.id==id}?.title?:id},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
        }} }
    }
}
@Composable private fun Knowledge(state:AppState,vm:LearnViewModel) {
    var query by rememberSaveable {mutableStateOf("")}
    val nodes=if(state.pcMode)state.remoteGraph else state.sharedGraph+state.data.graph().map {it.concept.copy(id=it.key,prerequisites=it.concept.prerequisites.map {p->"${it.sessionId}:$p"})}
    val filtered=nodes.filter {it.title.contains(query,true)||it.statement.contains(query,true)}
    LazyColumn(Modifier.testTag("knowledge"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item {Text(state.syncStatus,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item {Text("Knowledge graph",style=MaterialTheme.typography.headlineMedium);Text("${nodes.size} propositions in your library.",color=MaterialTheme.colorScheme.onSurfaceVariant)}
        if(nodes.isNotEmpty()) item {GraphCanvas(nodes.take(18))}
        item {OutlinedTextField(value=query,onValueChange={query=it},label={Text("Find an idea")},leadingIcon={Icon(Icons.Outlined.Search,null)},modifier=Modifier.fillMaxWidth())}
        if(filtered.isEmpty()) item {Text(if(query.isBlank()) "Your knowledge graph grows as you learn. Try the sample lesson or connect your PC." else "No matching ideas.")}
        items(filtered,key={it.id}) {n-> OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {Text(n.title,fontWeight=FontWeight.SemiBold);SelectionContainer {Text(n.statement)};if(n.prerequisites.isNotEmpty())Text("Connected to: "+n.prerequisites.mapNotNull {id->nodes.find{it.id==id}?.title}.joinToString(),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)}}}
    }
}
@Composable private fun GraphCanvas(nodes:List<Concept>) {
    val color=MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(220.dp).background(MaterialTheme.colorScheme.surfaceVariant,RoundedCornerShape(24.dp)).semantics {contentDescription="Overview of ${nodes.size} connected ideas. Full text is listed below."}) {
        val radius=size.minDimension*0.36f
        val points=nodes.indices.map {i->val angle=2*Math.PI*i/nodes.size-Math.PI/2;Offset(size.width/2+cos(angle).toFloat()*radius,size.height/2+sin(angle).toFloat()*radius)}
        nodes.forEachIndexed {i,n->n.prerequisites.forEach {id->val j=nodes.indexOfFirst {it.id==id};if(j>=0)drawLine(color.copy(alpha=.35f),points[j],points[i],3f)}}
        points.forEachIndexed {i,p->drawCircle(color,12f,p);drawContext.canvas.nativeCanvas.drawText("${i+1}",p.x-5,p.y-18,android.graphics.Paint().apply {this.color=android.graphics.Color.GRAY;textSize=22f})}
    }
}
@Composable private fun Practice(state:AppState,start:()->Unit,library:()->Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        Icon(Icons.Outlined.Psychology,null,Modifier.size(64.dp),tint=MaterialTheme.colorScheme.primary)
        Text("Turn understanding\ninto intuition.",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.SemiBold)
        Text("Work through varied problems, explain your reasoning, and ask for a hint when you need one.",style=MaterialTheme.typography.bodyLarge)
        Text(if(state.pcMode) "Using your live PC connection." else "Runs on this phone with ChatGPT. Your PC can stay off.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick=start,enabled=!state.busy&&(!state.pcMode||state.paired),modifier=Modifier.fillMaxWidth()) {Text("Start practicing")}
        OutlinedButton(onClick=library,modifier=Modifier.fillMaxWidth()) {Text("Resume a session")}
    }
}
@Composable private fun Settings(state:AppState,vm:LearnViewModel) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val uri=LocalUriHandler.current
    var url by rememberSaveable(state.provider.baseUrl) {mutableStateOf(state.provider.baseUrl)}
    var model by rememberSaveable(state.provider.model) {mutableStateOf(state.provider.model)}
    var key by remember {mutableStateOf("")}
    var pcUrl by rememberSaveable(state.pcUrl) {mutableStateOf(state.pcUrl)}
    var token by remember {mutableStateOf("")}
    var syncCode by remember {mutableStateOf("")}
    var advanced by rememberSaveable {mutableStateOf(false)}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {target->if(target!=null)scope.launch {runCatching {withContext(Dispatchers.IO) {context.contentResolver.openOutputStream(target)?.use {it.write(vm.export().toByteArray())} ?: error("Could not open file")}}.onSuccess{vm.report("Phone library exported without credentials")}.onFailure{vm.report("Export failed: ${it.message}")}}}
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {target->if(target!=null)scope.launch {runCatching {withContext(Dispatchers.IO) {context.contentResolver.openInputStream(target)?.use {input ->
        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true) {val count=input.read(buffer);if(count<0)break;require(out.size()+count<=80_000_000){"Backup exceeds 80 MB"};out.write(buffer,0,count)}
        out.toString("UTF-8")
    }?:error("Could not open file")}}.onSuccess(vm::import).onFailure{vm.report("Import failed: ${it.message}")}}}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Text("Your learning, everywhere.",style=MaterialTheme.typography.headlineMedium)
        Text("CHATGPT",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if(state.chatGptConnected)"Connected as ${state.chatGptEmail.ifBlank {"ChatGPT account"}}" else "Sign in on this phone to use your existing ChatGPT subscription. Requests count toward your ChatGPT allowance.")
        Button(onClick=vm::signInChatGpt,enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Text(if(state.chatGptConnected)"Reconnect ChatGPT" else "Continue with ChatGPT")}
        if(state.busy)TextButton(onClick=vm::cancel){Text("Cancel current request")}
        if(state.chatGptConnected) {
            Text("Model: ${state.chatGptModel.ifBlank {"Choose below"}}",style=MaterialTheme.typography.bodyMedium)
            TextButton(onClick=vm::refreshModels,enabled=!state.busy){Text("Load available models")}
            state.chatGptModels.forEach {m->OutlinedCard(onClick={vm.chooseModel(m.slug)},enabled=!state.busy,modifier=Modifier.fillMaxWidth()){
                Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){RadioButton(selected=state.chatGptModel==m.slug,onClick=null);Text(m.display_name.ifBlank {m.slug},Modifier.padding(start=10.dp))}
            }}
            TextButton(onClick=vm::signOutChatGpt,enabled=!state.busy){Text("Sign out on this phone")}
        }
        HorizontalDivider()
        Text("PRIVATE CLOUD SYNC",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(state.syncStatus)
        Text("Lessons are saved on each device and encrypted before upload. Your PC catches up when LearnHarness opens. ChatGPT credentials stay on this device.",style=MaterialTheme.typography.bodyMedium)
        if(state.syncConfigured)Button(onClick=vm::sync,enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Text("Sync now")}
        else {
            OutlinedTextField(value=syncCode,onValueChange={syncCode=it},label={Text("Private sync setup code")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
            Button(onClick={vm.setupSync(syncCode);syncCode=""},enabled=!state.busy&&syncCode.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text("Connect cloud sync")}
        }
        HorizontalDivider()
        TextButton(onClick={advanced=!advanced}){Text(if(advanced)"Hide earlier connection settings" else "Earlier phone lessons and live PC connection")}
        if(advanced) {
        SourceSwitch(state,vm)
        Text("Your PC, in your pocket.",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
        Text("Install the PC bridge update. In LearnHarness on PC choose LearnHarness → Connect Android phone. Connect Tailscale on both devices, then enter the HTTPS address and pairing token.")
        OutlinedTextField(value=pcUrl,onValueChange={pcUrl=it},label={Text("PC HTTPS address")},placeholder={Text("https://your-pc.your-tailnet.ts.net")},singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(value=token,onValueChange={token=it},label={Text(if(state.paired) "Pairing token (saved; leave blank to keep)" else "Pairing token")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
        Button(onClick={vm.pair(pcUrl,token);token=""},enabled=!state.busy&&pcUrl.isNotBlank(),modifier=Modifier.fillMaxWidth()) {Text("Connect and test")}
        if(state.paired) TextButton(onClick=vm::disconnect,enabled=!state.busy) {Text("Forget this PC")}
        TextButton(onClick={uri.openUri("https://tailscale.com/download")}) {Text("Get Tailscale for PC and Android")}
        HorizontalDivider()
        Text("Independent phone lessons",style=MaterialTheme.typography.titleLarge)
        Text("Optional: connect an OpenAI-compatible chat-completions provider. This sends your lesson context to that provider. PC sessions use your PC's setup instead.",style=MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {TextButton(onClick={url="https://openrouter.ai/api/v1"}) {Text("OpenRouter")};TextButton(onClick={url="https://api.openai.com/v1"}) {Text("OpenAI")};TextButton(onClick={url="https://api.deepseek.com/v1"}) {Text("DeepSeek")}}
        OutlinedTextField(value=url,onValueChange={url=it},label={Text("HTTPS API base URL")},singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(value=model,onValueChange={model=it},label={Text("Model ID")},singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(value=key,onValueChange={key=it},label={Text(if(state.hasKey) "API key (saved; leave blank to keep)" else "API key")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
        Text("Changing an address clears its saved key unless you enter a new one. Keys are encrypted with Android Keystore.",style=MaterialTheme.typography.bodySmall)
        Button(onClick={vm.saveProvider(url,model,key);key=""},enabled=!state.busy,modifier=Modifier.fillMaxWidth()) {Text("Save provider")}
        if(state.hasKey) TextButton(onClick=vm::clearKey,enabled=!state.busy) {Text("Remove API key")}
        }
        HorizontalDivider()
        Text("Your local library",style=MaterialTheme.typography.titleLarge)
        Text("Your backup includes shared lessons, progress, knowledge and the original phone records. Login credentials and private sync keys are excluded.",style=MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick={export.launch("learnharness-phone-backup.json")},enabled=!state.busy,modifier=Modifier.fillMaxWidth()) {Text("Export all lessons")}
        OutlinedButton(onClick={import.launch(arrayOf("application/json"))},enabled=!state.busy,modifier=Modifier.fillMaxWidth()) {Text("Import phone backup")}
        HorizontalDivider()
        Text("LearnHarness Android · 0.2.0",fontWeight=FontWeight.Medium)
        Text("Native Kotlin & Jetpack Compose. Adapted from Amos Blomqvist's LearnHarness. AGPL-3.0-only. AI-generated explanations can be wrong.",style=MaterialTheme.typography.bodySmall)
        TextButton(onClick={uri.openUri("https://github.com/AtecHEvolutioN/learnharness/tree/android-native")}) {Text("Source code and license")}
    }
}

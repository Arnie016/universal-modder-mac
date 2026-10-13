// SPDX-License-Identifier: GPL-2.0-or-later
import AppKit
import SceneKit
import JavaScriptCore

struct UnitState: Decodable { let id:Int; let team:Int; let x:Double; let z:Double; let hp:Double; let maxHp:Double; let activeAt:Double? }
struct BattleState: Decodable { let epoch:Int; let tick:Int; let time:Double; let units:[UnitState]; let view:String; let possessed:Int; let status:String; let core:Double; let kills:Int }
enum HostFailure: Error, CustomStringConvertible {
    case message(String)
    var description:String { switch self { case .message(let s):return s } }
}
final class BattleRuntime {
    let context:JSContext
    var failure:String?
    init() throws {
        guard let context=JSContext() else { throw HostFailure.message("JavaScriptCore could not start.") }
        self.context=context
        context.exceptionHandler = { [weak self] _, error in self?.failure=error?.toString() }
        let executable=URL(fileURLWithPath:CommandLine.arguments[0]).standardizedFileURL
        let fallback=executable.deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Resources/host.js")
        let source=Bundle.main.url(forResource:"host",withExtension:"js") ?? fallback
        context.evaluateScript(try String(contentsOf:source,encoding:.utf8),withSourceURL:source)
        if let error=failure { throw HostFailure.message(error) }
    }
    func call(_ name:String,_ args:[String:Any]?=nil) throws -> String {
        let allowed=["capabilities","observe","order","submit","tick","advance","viewpoint","reset","save","load","formation"]
        guard allowed.contains(name) else { throw HostFailure.message("Unknown native capability.") }
        let input = try args.map { String(data:try JSONSerialization.data(withJSONObject:$0),encoding:.utf8)! }
        return try callJSON(name,input)
    }
    func callJSON(_ name:String,_ json:String?) throws -> String {
        failure=nil;context.exception=nil
        guard let fn=context.objectForKeyedSubscript("nativeRemix")?.forProperty(name), !fn.isUndefined else { throw HostFailure.message("Missing native adapter method.") }
        let result=fn.call(withArguments:json.map{[$0]} ?? [])
        if let error=failure { throw HostFailure.message(error) }
        guard let text=result?.toString() else { throw HostFailure.message("Native adapter returned no state.") }
        return text
    }
    func state() throws -> BattleState { try decode(call("observe")) }
    func decode(_ json:String) throws -> BattleState { try JSONDecoder().decode(BattleState.self,from:Data(json.utf8)) }
}

final class BattleView:SCNView {
    var held=Set<String>()
    var action:((String)->Void)?
    var pointer:((CGPoint)->Void)?
    var look:((CGFloat)->Void)?
    var lastX:CGFloat=0
    override var acceptsFirstResponder:Bool { true }
    override func keyDown(with e:NSEvent) {
        if e.modifierFlags.contains(.command) { super.keyDown(with:e);return }
        let key=e.charactersIgnoringModifiers?.lowercased() ?? ""
        held.insert(key)
        if e.modifierFlags.contains(.shift) { held.insert("shift") }
        if !e.isARepeat { action?(e.keyCode==48 ? "tab":key) }
    }
    override func keyUp(with e:NSEvent) { held.remove(e.charactersIgnoringModifiers?.lowercased() ?? "");if !e.modifierFlags.contains(.shift){held.remove("shift")} }
    override func flagsChanged(with e:NSEvent) { if e.modifierFlags.contains(.shift){held.insert("shift")}else{held.remove("shift")} }
    override func mouseDown(with e:NSEvent) { window?.makeFirstResponder(self);let p=convert(e.locationInWindow,from:nil);lastX=p.x;pointer?(p) }
    override func mouseDragged(with e:NSEvent) { let x=convert(e.locationInWindow,from:nil).x;look?(x-lastX);lastX=x }
    override func resignFirstResponder()->Bool { held.removeAll();return super.resignFirstResponder() }
}

final class RenderCounter:NSObject,SCNSceneRendererDelegate {
    private let lock=NSLock()
    private var frames=0
    func renderer(_ renderer:SCNSceneRenderer,didRenderScene scene:SCNScene,atTime time:TimeInterval){lock.lock();frames+=1;lock.unlock()}
    func count()->Int {lock.lock();defer{lock.unlock()};return frames}
}
final class NativeGame:NSObject,NSApplicationDelegate {
    let runtime:BattleRuntime
    var window:NSWindow!
    let scene=SCNScene()
    let camera=SCNNode()
    let renderCounter=RenderCounter()
    let battlefield=BattleView(frame:.zero,options:[SCNView.Option.preferredRenderingAPI.rawValue:NSNumber(value:SCNRenderingAPI.metal.rawValue)])
    let stats=NSTextField(labelWithString:"")
    let notice=NSTextField(labelWithString:"Hold the sanctuary. Command eight guardians, then fight beside them.")
    let mode=NSTextField(labelWithString:"COMMAND VIEW")
    let health=NSProgressIndicator()
    var nodes=[Int:SCNNode]()
    var bars=[Int:SCNNode]()
    var timer:Timer?
    var running=false
    var selected:Int?
    var yaw:Double=0
    var state:BattleState!
    var frames=0
    let proofDirectory:URL
    init(runtime:BattleRuntime,proofDirectory:URL){self.runtime=runtime;self.proofDirectory=proofDirectory;super.init()}
    func applicationDidFinishLaunching(_ notification:Notification) {
        do {
            state=try runtime.state();buildScene();buildWindow();render(state)
            timer=Timer.scheduledTimer(withTimeInterval:1.0/60.0,repeats:true){[weak self] _ in self?.update()}
            if let timer=timer { RunLoop.main.add(timer,forMode:.common) }
            window.makeKeyAndOrderFront(nil);window.makeFirstResponder(battlefield)
        } catch { show(error) }
    }
    func applicationShouldTerminateAfterLastWindowClosed(_ sender:NSApplication)->Bool { true }
    func applicationWillTerminate(_ notification:Notification){timer?.invalidate()}
    func button(_ title:String,_ selector:Selector)->NSButton {
        let b=NSButton(title:title,target:self,action:selector);b.bezelStyle = .rounded;b.font = .systemFont(ofSize:13,weight:.medium);return b
    }
    func buildWindow() {
        window=NSWindow(contentRect:NSRect(x:0,y:0,width:1200,height:800),styleMask:[.titled,.closable,.miniaturizable,.resizable],backing:.buffered,defer:false)
        window.title="Tactics Possession · Native Mac Prototype";window.minSize=NSSize(width:940,height:660);window.center()
        let root=NSView();window.contentView=root
        let title=NSTextField(labelWithString:"TACTICS POSSESSION");title.font = .systemFont(ofSize:22,weight:.bold)
        let subtitle=NSTextField(labelWithString:"Command from above. Fight as a guardian. One shared battle.");subtitle.font = .systemFont(ofSize:12);subtitle.textColor = .secondaryLabelColor
        let heading=NSStackView(views:[title,subtitle]);heading.orientation = .vertical;heading.alignment = .leading;heading.spacing=4
        let top=NSStackView(views:[heading,button("Begin / Pause  P",#selector(pause)),button("Restart  R",#selector(restart))]);top.spacing=12;top.distribution = .fill
        let controls=NSStackView(views:[button("Rally at gate  Q",#selector(rally)),button("Hold position  G",#selector(guardSquad)),button("Possess / Command  Tab",#selector(possess)),button("Save battle",#selector(save)),button("Load save",#selector(load))]);controls.spacing=8
        let roster=NSStackView();roster.spacing=7
        roster.addArrangedSubview(NSTextField(labelWithString:"Fight as:"))
        for id in 1...8 { let b=button("Guardian \(id)",#selector(selectGuardian(_:)));b.tag=id;roster.addArrangedSubview(b) }
        stats.font = .monospacedDigitSystemFont(ofSize:16,weight:.semibold)
        mode.font = .systemFont(ofSize:13,weight:.bold);mode.textColor = .systemYellow
        health.isIndeterminate=false;health.minValue=0;health.maxValue=300;health.style = .bar
        notice.font = .systemFont(ofSize:13);notice.lineBreakMode = .byTruncatingTail
        let instructions=NSTextField(labelWithString:"Click ground or enemies to command · WASD moves · Space strikes · Shift sprints · Drag to look")
        instructions.font = .systemFont(ofSize:12);instructions.textColor = .secondaryLabelColor
        let bar=NSStackView(views:[stats,mode,health]);bar.spacing=20
        let stack=NSStackView(views:[top,bar,battlefield,controls,roster,notice,instructions]);stack.orientation = .vertical;stack.alignment = .leading;stack.spacing=12;stack.translatesAutoresizingMaskIntoConstraints=false
        root.addSubview(stack)
        for view in [top,bar,battlefield,controls,roster,notice,instructions] { view.translatesAutoresizingMaskIntoConstraints=false;view.widthAnchor.constraint(equalTo:stack.widthAnchor).isActive=true }
        NSLayoutConstraint.activate([stack.leadingAnchor.constraint(equalTo:root.leadingAnchor,constant:20),stack.trailingAnchor.constraint(equalTo:root.trailingAnchor,constant:-20),stack.topAnchor.constraint(equalTo:root.topAnchor,constant:18),stack.bottomAnchor.constraint(equalTo:root.bottomAnchor,constant:-18),battlefield.heightAnchor.constraint(greaterThanOrEqualToConstant:390),health.widthAnchor.constraint(greaterThanOrEqualToConstant:140)])
        battlefield.wantsLayer=true;battlefield.layer?.cornerRadius=16;battlefield.layer?.masksToBounds=true
        battlefield.delegate=renderCounter;battlefield.scene=scene;battlefield.pointOfView=camera;battlefield.backgroundColor=NSColor(calibratedRed:0.64,green:0.74,blue:0.69,alpha:1);battlefield.antialiasingMode = .multisampling4X;battlefield.preferredFramesPerSecond=60;battlefield.isPlaying=true
        battlefield.action={[weak self] key in guard let self=self else{return};switch key{case "q":self.rally();case "g":self.guardSquad();case "tab":self.possess();case "r":self.restart();case "p":self.pause();default:break}}
        battlefield.pointer={[weak self] point in self?.command(point)}
        battlefield.look={[weak self] delta in if self?.state.view=="soldier"{self?.yaw-=Double(delta)*0.006}}
        let menu=NSMenu();let item=NSMenuItem();menu.addItem(item);let appMenu=NSMenu();appMenu.addItem(withTitle:"Quit Tactics Possession",action:#selector(NSApplication.terminate(_:)),keyEquivalent:"q");item.submenu=appMenu;NSApp.mainMenu=menu
        NotificationCenter.default.addObserver(forName:NSWindow.didResignKeyNotification,object:window,queue:.main){[weak self] _ in self?.battlefield.held.removeAll()}
    }
    func material(_ r:CGFloat,_ g:CGFloat,_ b:CGFloat)->SCNMaterial { let m=SCNMaterial();m.diffuse.contents=NSColor(calibratedRed:r,green:g,blue:b,alpha:1);m.lightingModel = .physicallyBased;m.roughness.contents=0.8;return m }
    @discardableResult func shape(_ geometry:SCNGeometry,_ material:SCNMaterial,_ x:Double,_ y:Double,_ z:Double,_ parent:SCNNode?=nil)->SCNNode {
        geometry.materials=[material];let node=SCNNode(geometry:geometry);node.position=SCNVector3(x,y,z);(parent ?? scene.rootNode).addChildNode(node);return node
    }
    func buildScene() {
        let green=material(0.30,0.41,0.31),stone=material(0.66,0.61,0.49),wood=material(0.29,0.23,0.16),leaves=material(0.17,0.35,0.26),gold=material(0.84,0.67,0.36)
        shape(SCNBox(width:150,height:0.2,length:150,chamferRadius:0),green,0,-0.15,0)
        shape(SCNBox(width:9,height:0.04,length:80,chamferRadius:0),material(0.66,0.59,0.44),0,0,0)
        shape(SCNBox(width:15,height:0.03,length:145,chamferRadius:0),material(0.24,0.51,0.56),-51,-0.01,0)
        for (x,z,w,d) in [(-10.0,1.0,4.0,13.0),(10,1,4,13),(-6,13,4,3),(6,13,4,3)] {
            shape(SCNBox(width:w,height:2.8,length:d,chamferRadius:0.12),stone,x,1.4,z)
            for i in 0..<Int(ceil(d/2)) {shape(SCNBox(width:w+0.15,height:0.4,length:1.2,chamferRadius:0.04),stone,x,3.0,z-d/2+Double(i)*2)}
        }
        for x in [-5.0,5] {shape(SCNCylinder(radius:0.9,height:7),stone,x,3.5,-2);shape(SCNCylinder(radius:1.25,height:0.5),gold,x,7.15,-2)}
        shape(SCNBox(width:12,height:0.9,length:2,chamferRadius:0.15),stone,0,7.9,-2)
        shape(SCNBox(width:6,height:0.8,length:6,chamferRadius:0.15),stone,0,0.4,30)
        shape(SCNCylinder(radius:1.45,height:4),stone,0,2.5,30)
        let beacon=shape(SCNSphere(radius:1.15),gold,0,5.3,30);beacon.geometry?.firstMaterial?.emission.contents=NSColor.systemYellow
        for i in 0..<36 {let side=i%2==0 ? -1.0:1.0;let x=side*(20+Double(i*17%25)),z=Double(i*29%91)-45;shape(SCNCylinder(radius:0.28,height:4),wood,x,2,z);shape(SCNSphere(radius:2.4),leaves,x,5,z)}
        for i in 0..<14 {shape(SCNCone(topRadius:0,bottomRadius:12+Double(i%5),height:20+Double(i%3)*6),material(0.44,0.55,0.47),Double(i)*15-95,8,-65-Double(i%3)*10)}
        let sun=SCNNode();sun.light=SCNLight();sun.light?.type = .directional;sun.light?.intensity=1700;sun.light?.color=NSColor(calibratedRed:1,green:0.88,blue:0.68,alpha:1);sun.light?.castsShadow=true;sun.eulerAngles=SCNVector3(-0.9,-0.5,0);scene.rootNode.addChildNode(sun)
        let ambient=SCNNode();ambient.light=SCNLight();ambient.light?.type = .ambient;ambient.light?.intensity=550;ambient.light?.color=NSColor(calibratedRed:0.72,green:0.82,blue:1,alpha:1);scene.rootNode.addChildNode(ambient)
        scene.fogColor=NSColor(calibratedRed:0.64,green:0.74,blue:0.69,alpha:1);scene.fogStartDistance=80;scene.fogEndDistance=160
        camera.camera=SCNCamera();camera.camera?.fieldOfView=48;camera.camera?.zFar=250;camera.camera?.wantsHDR=true;camera.camera?.bloomIntensity=0.25;scene.rootNode.addChildNode(camera)
        for u in state.units {
            let unit=SCNNode(),team=material(u.team==1 ? 0.15:0.57,u.team==1 ? 0.37:0.25,u.team==1 ? 0.33:0.18)
            unit.name="unit-\(u.id)"
            shape(SCNCapsule(capRadius:0.34,height:1.4),team,0,1,0,unit)
            shape(SCNSphere(radius:0.28),material(0.77,0.59,0.42),0,1.86,0,unit)
            shape(SCNSphere(radius:0.31),material(0.71,0.72,0.68),0,2.03,0,unit)
            shape(SCNBox(width:0.12,height:0.32,length:0.5,chamferRadius:0.03),gold,0,2.2,0,unit)
            shape(SCNBox(width:0.12,height:1.05,length:0.12,chamferRadius:0.02),stone,0.52,1.3,0,unit)
            let shield=shape(SCNCylinder(radius:0.43,height:0.1),team,-0.51,1.15,0,unit);shield.eulerAngles.z = .pi/2
            let bar=shape(SCNBox(width:1,height:0.07,length:0.07,chamferRadius:0),u.team==1 ? material(0.65,0.86,0.60):material(0.91,0.58,0.44),0,2.65,0,unit)
            scene.rootNode.addChildNode(unit);nodes[u.id]=unit;bars[u.id]=bar
        }
    }
    func update() {
        do {
            if running && state.status=="playing" {
                let forward=(battlefield.held.contains("w") ? 1.0:0)-(battlefield.held.contains("s") ? 1:0)
                let side=(battlefield.held.contains("d") ? 1.0:0)-(battlefield.held.contains("a") ? 1:0)
                state=try runtime.decode(runtime.call("tick",["dx":-sin(yaw)*forward+cos(yaw)*side,"dz":-cos(yaw)*forward-sin(yaw)*side,"strike":battlefield.held.contains(" "),"sprint":battlefield.held.contains("shift")]))
                if state.status != "playing" {running=false;notice.stringValue=state.status=="won" ? "Victory. The sanctuary stands. Restart to fight again.":"Sanctuary lost. Restart for another attempt."}
            }
            render(state);frames+=1
        } catch { running=false;show(error) }
    }
    func render(_ w:BattleState) {
        for u in w.units {guard let node=nodes[u.id] else{continue};let old=node.position;node.position=SCNVector3(u.x,0,u.z);node.isHidden=u.hp<=0 || (u.activeAt ?? 0)>w.time || (w.view=="soldier" && w.possessed==u.id);if hypot(Double(old.x)-u.x,Double(old.z)-u.z)>0.001{node.eulerAngles.y=CGFloat(atan2(u.x-Double(old.x),u.z-Double(old.z)))};bars[u.id]?.scale.x=CGFloat(max(0,u.hp/u.maxHp))}
        if w.view=="soldier",let hero=w.units.first(where:{$0.id==w.possessed}){camera.position=SCNVector3(hero.x,1.9,hero.z);camera.eulerAngles=SCNVector3(0,yaw,0);mode.stringValue="GUARDIAN \(hero.id) · \(Int(ceil(hero.hp))) / 180 HP"}
        else {camera.position=SCNVector3(34,49,57);camera.look(at:SCNVector3(0,0,2));mode.stringValue=selected.map{"GUARDIAN \($0) SELECTED"} ?? "COMMAND VIEW · ALL GUARDIANS"}
        let remaining=max(0,Int(ceil(180-w.time))),allies=w.units.filter{$0.team==1 && $0.hp>0}.count,enemies=w.units.filter{$0.team==2 && $0.hp>0}.count
        stats.stringValue=String(format:"%d guardians   %d raiders   %d:%02d   Sanctuary %d%%",allies,enemies,remaining/60,remaining%60,Int(ceil(w.core/3)));health.doubleValue=w.core
    }
    func selectedIDs()->[Int] { if let id=selected,state.units.contains(where:{$0.id==id && $0.hp>0}){return [id]};return state.units.filter{$0.team==1 && $0.hp>0}.map{$0.id} }
    func order(_ action:String,_ payload:[String:Any]) {
        guard running,state.status=="playing" else{notice.stringValue="Press Begin before giving orders.";return}
        do { _=try runtime.call("order",["epoch":state.epoch,"ids":selectedIDs(),"action":action,"payload":payload]);state=try runtime.state();notice.stringValue="Squad order: \(action)." } catch {show(error)}
    }
    func command(_ point:CGPoint) {
        guard state.view=="overview" else{return}
        for hit in battlefield.hitTest(point,options:[:]) {var n:SCNNode?=hit.node;while let node=n{if let name=node.name,name.hasPrefix("unit-"),let id=Int(name.dropFirst(5)),let u=state.units.first(where:{$0.id==id}),u.team==2,u.hp>0{order("attack",["target":id]);return};n=node.parent}}
        let near=battlefield.unprojectPoint(SCNVector3(point.x,point.y,0)),far=battlefield.unprojectPoint(SCNVector3(point.x,point.y,1));let dy=Double(far.y-near.y);if abs(dy)<0.0001{return};let t = -Double(near.y)/dy
        order("move",["x":Double(near.x)+Double(far.x-near.x)*t,"z":Double(near.z)+Double(far.z-near.z)*t])
    }
    @objc func pause(){running.toggle();notice.stringValue=running ? "Battle underway. Rally at the gate, then possess a guardian.":"Paused. Press Begin / Pause to continue.";window.makeFirstResponder(battlefield)}
    @objc func rally(){order("attackMove",["x":0,"z":6]);window.makeFirstResponder(battlefield)}
    @objc func guardSquad(){order("stance",["name":"standground"]);window.makeFirstResponder(battlefield)}
    @objc func possess(){do{let id=selected ?? state.possessed;state=try runtime.decode(runtime.call("viewpoint",["epoch":state.epoch,"id":id,"view":state.view=="overview" ? "soldier":"overview"]));notice.stringValue="Same soldier, same battle. WASD moves; Space strikes.";window.makeFirstResponder(battlefield)}catch{show(error)}}
    @objc func restart(){do{state=try runtime.decode(runtime.call("reset"));selected=nil;running=true;battlefield.held.removeAll();notice.stringValue="New encounter. Hold the sanctuary.";window.makeFirstResponder(battlefield)}catch{show(error)}}
    @objc func selectGuardian(_ sender:NSButton){selected = selected==sender.tag ? nil:sender.tag;notice.stringValue=selected.map{"Guardian \($0) selected. Tab to possess."} ?? "All living guardians selected.";window.makeFirstResponder(battlefield)}
    @objc func save(){do{try FileManager.default.createDirectory(at:proofDirectory,withIntermediateDirectories:true);try runtime.call("save").write(to:proofDirectory.appendingPathComponent("battle-save.json"),atomically:true,encoding:.utf8);let receipt:[String:Any]=["renderer":"SceneKit","backend":battlefield.renderingAPI == .metal ? "Metal":"OTHER","renderedFrames":renderCounter.count(),"updateFrames":frames,"epoch":state.epoch,"tick":state.tick,"view":state.view,"possessed":state.possessed,"running":running,"humanPlaytest":"NOT_TESTED"]
        try JSONSerialization.data(withJSONObject:receipt,options:[.prettyPrinted,.sortedKeys]).write(to:proofDirectory.appendingPathComponent("native-window-receipt.json"),options:.atomic)
        notice.stringValue="Battle saved locally."}catch{show(error)}}
    @objc func load(){do{state=try runtime.decode(runtime.callJSON("load",String(contentsOf:proofDirectory.appendingPathComponent("battle-save.json"),encoding:.utf8)));running=false;selected=nil;notice.stringValue="Saved battle restored and paused. Press Begin to continue.";render(state)}catch{show(error)}}
    func show(_ error:Error){notice.stringValue="\(error)";if window==nil{fputs("\(error)\n",stderr)}}
}

enum NativeVerifier {
    static func run(_ runtime:BattleRuntime) throws {
        var checks=[String]()
        func check(_ yes:Bool,_ name:String) throws {if !yes{throw HostFailure.message("FAILED: \(name)")};checks.append(name)}
        let capabilities=try runtime.call("capabilities");try check(capabilities.contains("native-valley") && capabilities.contains("78ca04310"),"native descriptor and real donor pin")
        var state=try runtime.state();let before=state.units[0].z
        let receipt=try runtime.call("order",["epoch":state.epoch,"ids":[1,2],"action":"move","payload":["x":0,"z":18]])
        try check(receipt.contains("\"walk\""),"real upstream walk command in JavaScriptCore")
        _=try runtime.call("advance",["ticks":60]);state=try runtime.state();try check(state.units[0].z<before,"native runtime movement")
        let save=try runtime.call("save"),identity=try runtime.call("observe")
        _=try runtime.call("viewpoint",["epoch":state.epoch,"id":1,"view":"soldier"])
        _=try runtime.call("viewpoint",["epoch":state.epoch,"id":1,"view":"overview"])
        try check(try runtime.call("observe")==identity,"view switch preserves exact world")
        _=try runtime.call("reset");_=try runtime.callJSON("load",save)
        try check(try runtime.call("observe")==identity,"native save/reset/reload identity")
        do{_=try runtime.call("order",["epoch":state.epoch,"ids":[9],"action":"move","payload":["x":0,"z":18]]);throw HostFailure.message("Enemy order accepted")}catch let e as HostFailure {if e.description=="Enemy order accepted"{throw e};checks.append("enemy ownership rejected")}
        do{_=try runtime.call("order",["epoch":999,"ids":[1],"action":"move","payload":["x":0,"z":18]]);throw HostFailure.message("Stale order accepted")}catch let e as HostFailure {if e.description=="Stale order accepted"{throw e};checks.append("stale epoch rejected")}
        let envelope:[String:Any]=["v":1,"epoch":state.epoch,"seq":20,"owner":1,"command":["type":"walk","entities":[1],"x":-10,"z":0]]
        try check(try runtime.call("submit",envelope).contains("\"accepted\":false"),"obstructed destination rejected")
        _=try runtime.call("reset");state=try runtime.state()
        _=try runtime.call("order",["epoch":state.epoch,"ids":[1,2,3,4,5,6,7,8],"action":"attackMove","payload":["x":0,"z":6]])
        for _ in 0..<19 {_=try runtime.call("advance",["ticks":600]);if try runtime.state().status != "playing"{break}}
        state=try runtime.state();try check(state.kills>0 && state.status != "playing","actual complete native combat encounter")
        let combatSave=try runtime.call("save");_=try runtime.call("reset");_=try runtime.callJSON("load",combatSave)
        try check(try runtime.call("save")==combatSave,"combat JSON persistence")
        // Drive the actual JavaScriptCore host with constrained save fixtures.
        func fixture(_ edits:(inout [[String:Any]])->Void) throws -> BattleState {
            _=try runtime.call("reset")
            var saved=try JSONSerialization.jsonObject(with:Data(runtime.call("save").utf8)) as! [String:Any]
            var world=saved["world"] as! [String:Any]
            var units=world["units"] as! [[String:Any]]
            for i in units.indices {units[i]["stance"]="passive";units[i]["order"]=NSNull()}
            edits(&units);world["units"]=units;world["view"]="soldier";world["possessed"]=1;saved["world"]=world
            let json=String(data:try JSONSerialization.data(withJSONObject:saved),encoding:.utf8)!
            return try runtime.decode(runtime.callJSON("load",json))
        }
        _=try fixture{u in u[0]["x"] = -6;u[0]["z"]=0}
        for _ in 0..<120 {_=try runtime.call("tick",["dx":-1,"dz":0])}
        state=try runtime.state();try check(state.units[0].x > -7.5 && state.units[0].x < -7,"possessed body stops at host wall")
        _=try fixture{u in u[0]["x"]=0;u[0]["z"]=0;u[8]["x"]=0;u[8]["z"]=2.8}
        _=try runtime.call("tick",["dx":0,"dz":0,"strike":true]);state=try runtime.state()
        try check(state.units[8].hp==95,"native first-person strike changes same enemy health")
        for _ in 0..<10 {_=try runtime.call("tick",["dx":0,"dz":0,"strike":true])}
        try check(try runtime.state().units[8].hp==95,"native strike cooldown prevents per-frame damage")
        _=try fixture{u in u[0]["x"] = -12.6;u[0]["z"] = -4.4;u[8]["x"] = -11.1;u[8]["z"] = -6.1}
        _=try runtime.call("tick",["dx":0,"dz":0,"strike":true])
        try check(try runtime.state().units[8].hp==150,"native strike cannot pass through wall corner")
        _=try runtime.call("reset")
        var bad=try JSONSerialization.jsonObject(with:Data(runtime.call("save").utf8)) as! [String:Any]
        var badWorld=bad["world"] as! [String:Any];badWorld["kills"] = -1;bad["world"]=badWorld
        let badJSON=String(data:try JSONSerialization.data(withJSONObject:bad),encoding:.utf8)!
        do{_=try runtime.callJSON("load",badJSON);throw HostFailure.message("Invalid save accepted")}catch let e as HostFailure {if e.description=="Invalid save accepted"{throw e};checks.append("malformed save rejected without evaluating imported code")}
        _=try runtime.call("reset")
        let formationJSON="{\"schema\":\"remix-formation/1\",\"id\":\"native-column\",\"name\":\"Native column\",\"slots\":[{\"x\":-0.65,\"z\":0},{\"x\":0.65,\"z\":0},{\"x\":-0.65,\"z\":1.5},{\"x\":0.65,\"z\":1.5},{\"x\":-0.65,\"z\":3},{\"x\":0.65,\"z\":3},{\"x\":-0.65,\"z\":4.5},{\"x\":0.65,\"z\":4.5}]}"
        _=try runtime.callJSON("formation",formationJSON);state=try runtime.state()
        _=try runtime.call("order",["epoch":state.epoch,"ids":[1,2,3,4,5,6,7,8],"action":"move","payload":["x":0,"z":18]])
        let column=try runtime.call("observe")
        try check(column.contains("native-column") && column.contains("22.5"),"native formation changes real donor destinations")
        let columnSave=try runtime.call("save");_=try runtime.call("reset");_=try runtime.callJSON("load",columnSave)
        try check(try runtime.call("save")==columnSave,"native creator formation persistence")
        let beforeBadLayout=try runtime.call("save")
        do{_=try runtime.callJSON("formation","{\"schema\":\"remix-formation/1\"}");throw HostFailure.message("Bad formation accepted")}catch let e as HostFailure {if e.description=="Bad formation accepted"{throw e}}
        try check(try runtime.call("save")==beforeBadLayout,"native invalid layout leaves battle unchanged")
        state=try fixture{u in u[0]["x"]=0;u[0]["z"]=20;u[0]["stance"]="defensive";u[8]["x"]=0;u[8]["z"]=17}
        _=try runtime.call("viewpoint",["epoch":state.epoch,"id":1,"view":"overview"])
        _=try runtime.call("order",["epoch":state.epoch,"ids":[1],"action":"move","payload":["x":-8,"z":28]])
        _=try runtime.call("advance",["ticks":60]);state=try runtime.state()
        try check(state.units[0].z>22 && state.units[0].x < -2,"native move obeys retreat instead of chasing")
        let stoppedX=state.units[0].x,stoppedZ=state.units[0].z
        _=try runtime.call("order",["epoch":state.epoch,"ids":[1],"action":"stance","payload":["name":"standground"]])
        _=try runtime.call("advance",["ticks":60]);state=try runtime.state()
        try check(abs(state.units[0].x-stoppedX)<0.000001 && abs(state.units[0].z-stoppedZ)<0.000001,"native hold cancels active travel")
        let receiptObject:[String:Any]=["runtime":"JavaScriptCore","host":"native-valley","checks":checks,"passed":checks.count,"nativeWindow":"NOT_TESTED_BY_THIS_VERIFIER","full0ADEngine":false,"humanPlaytest":"NOT_TESTED"]
        print(String(data:try JSONSerialization.data(withJSONObject:receiptObject,options:[.prettyPrinted,.sortedKeys]),encoding:.utf8)!)
    }
}

@main struct NativeEntry {
    static func main() {
        do {
            let runtime=try BattleRuntime()
            if CommandLine.arguments.contains("--verify"){try NativeVerifier.run(runtime);return}
            let defaultDirectory=FileManager.default.urls(for:.applicationSupportDirectory,in:.userDomainMask)[0].appendingPathComponent("TacticsPossessionNativePrototype")
            let flag=CommandLine.arguments.firstIndex(of:"--state-dir")
            let directory=flag.flatMap{$0+1<CommandLine.arguments.count ? URL(fileURLWithPath:CommandLine.arguments[$0+1]):nil} ?? defaultDirectory
            let app=NSApplication.shared;app.setActivationPolicy(.regular)
            let delegate=NativeGame(runtime:runtime,proofDirectory:directory);app.delegate=delegate
            withExtendedLifetime(delegate){app.run()}
        } catch {fputs("Native host failed: \(error)\n",stderr);exit(1)}
    }
}

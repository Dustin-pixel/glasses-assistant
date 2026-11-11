// =====================================================================
//  Smart-glasses clip-on, v2: parametric OpenSCAD model
//
//  Bought parts modeled in place:
//    - Seeed XIAO ESP32S3 Sense (board + camera board + camera)
//    - Adafruit 1.28" round TFT, GC9A01A (#6178), outline from Adafruit's PCB files
//    - 25 mm biconvex Cardboard lens, 45 mm focal length
//    - 3.7 V 1000 mAh LiPo, 8.5 x 25 x 40 mm
//    - M2 screws
//
//  Axes (right side of the glasses, wearer's view):
//    +X forward (where you look), +Y inward toward the nose, +Z up.
//    The glasses' temple arm runs along X at Y = 0, Z = 0.
//
//  part = "assembly" previews everything (F5).
//  Printable parts: clip, arm, eyepiece, sled, front_cap, battery_clip.
//  Set print_orient = true before exporting to lay each part flat for printing.
//  All dimensions in mm. Measure your glasses and edit the MEASUREMENTS block.
// =====================================================================

part = "assembly";
print_orient = false;
focus = 0;          // screen position shown in the assembly, -1 (near) .. 1 (far)

// ---------- MEASUREMENTS (your glasses; replace with your own) ----------
temple_w      = 4.5;  // temple arm thickness, side to side, where the clip sits
temple_h      = 9.0;  // temple arm height there
clip_to_front = 15;   // front edge of the clip -> front face of the glasses frame
frame_clear   = 8;    // gap between the frame front and the eyepiece
eye_to_temple = 32;   // temple centreline -> pupil centre, sideways
eye_drop      = 2;    // pupil sits this far BELOW the temple centreline

// ---------- LENS (25 mm Cardboard lens) ----------
lens_d   = 25;
lens_edge = 2;        // rim thickness
lens_f   = 45;        // focal length
lens_n   = 1.49;      // acrylic
focus_nominal = 42;   // lens centre -> screen glass; a bit under lens_f puts the image ~0.6 m away
focus_travel  = 8;    // total focus adjustment

// ---------- SCREEN: Adafruit 1.28" round TFT #6178 ----------
// Board coords: origin at the screen centre, +v toward the round top, pin tab at the bottom.
db_r      = 18.0;     // round part of the board
db_tab_w  = 28.83;    // pin tab width
db_bot    = -24.13;   // bottom edge of the tab
db_pcb    = 1.6;
db_comp   = 2.6;      // tallest part on the back (microSD socket)
glass_d   = 35.6;
glass_t   = 2.2;
active_d  = 32.4;
db_holes  = [[11.43, -17.78], [-11.43, -17.78]];   // 2.5 mm plated holes
db_pads_v = -22.225;  // row of 11 pads, 2.54 mm pitch
db_pads_n = 11;

// ---------- XIAO ESP32S3 Sense ----------
xiao_l     = 21;      // along the pins (USB-C at one end)
xiao_w     = 17.8;
xiao_stack = 15;      // metal can -> camera lens front
xiao_pcb   = 1.2;
xiao_can   = 2.4;
sense_gap  = 3.0;     // board-to-board connector height
sense_pcb  = 1.2;
cam_mod    = 8.5;     // camera module size
cam_lens_d = 7;
cam_dy = 0;           // camera offset from the board centre, if yours isn't centred
cam_dz = 0;

// ---------- BATTERY ----------
bat_l = 40; bat_w = 25; bat_t = 8.5;

// ---------- PRINT / HARDWARE ----------
wall      = 2.0;      // clips
tube_wall = 1.6;      // eyepiece tube (4 lines at 0.4 mm)
fit       = 0.3;      // clearance for sliding fits
snap      = 1.0;      // clip opening is this much narrower than the temple
bolt_d    = 2.3;      // M2 clearance
tap_d     = 1.8;      // M2 self-tapping into plastic
clip_len  = 40;
tab_rise  = 6;        // lifts the arm above the frame hinge
slot_len  = 12;       // sideways adjustment of the arm
ep_tab_h  = 6;        // eyepiece hinge height above the tube
sled_plate = 3;       // plate the screen glass sits in
sled_len   = 8;
sled_wall  = 1.5;
sled_floor = 4;       // thick enough for the focus screw to bite
cap_wall   = 2;
cap_skirt  = 6;
$fn = 64;

// =====================================================================
// DERIVED
// =====================================================================
lens_R   = 2 * (lens_n - 1) * lens_f;                          // surface radius
lens_sag = lens_R - sqrt(lens_R * lens_R - (lens_d / 2) * (lens_d / 2));
lens_tc  = lens_edge + 2 * lens_sag;                           // centre thickness
eye_wall = max(1.5, lens_sag + 0.6);
lens_pocket = lens_edge + 0.4;
lens_c   = eye_wall + lens_pocket / 2;                         // lens centre plane

db_w  = 2 * db_r;                                              // board width (round part)
sled_y  = db_w + 2 * fit + 2 * sled_wall;
sled_z0 = db_bot - fit - sled_floor;
sled_z1 = db_r + fit + sled_wall;
bay_w  = sled_y + 2 * fit;
bay_z0 = sled_z0 - fit;
bay_z1 = sled_z1 + fit;

glass_in_sled = sled_plate - glass_t;                          // glass face inside the sled
sled_x_nom = lens_c + focus_nominal - glass_in_sled;
sled_x_min = sled_x_nom - focus_travel / 2;
sled_x_max = sled_x_nom + focus_travel / 2;
function sled_x_at(f) = sled_x_nom + f * focus_travel / 2;
sled_x = sled_x_at(focus);

elec_x0   = sled_x_max + sled_len + 2;                         // electronics bay
EL        = elec_x0 + xiao_stack + cap_wall;                   // eyepiece length
xiao_back = EL - cap_wall - xiao_stack;                        // back of the XIAO's can
cam_front = EL - cap_wall;                                     // camera lens tip
sense_front = xiao_back + xiao_can + xiao_pcb + sense_gap + sense_pcb;
cam_h     = cam_front - sense_front;                           // camera height above its board

ty0 = -bay_w / 2 - tube_wall;  ty1 = bay_w / 2 + tube_wall;    // tube outside, local
tz0 = bay_z0 - tube_wall;      tz1 = bay_z1 + tube_wall;
tube_len = EL - cap_wall;                                      // the front cap closes the rest
focus_screw_y = 0;
focus_screw_dx = sled_len / 2;

// placement on the glasses
x0       = clip_to_front + frame_clear;                        // eyepiece eye face
hinge_x  = x0 + 5;
reach    = hinge_x + 4;
clip_top = temple_h / 2 + wall;
tab_top  = clip_top + tab_rise;
ep_y_min = eye_to_temple + ty0;                                // eyepiece temple-side face, world
arm_end  = ep_y_min + slot_len / 2;
ep_hole_z = -eye_drop + tz1 + ep_tab_h;                        // eyepiece hinge, world
pitch_dz = ep_hole_z - tab_top;

echo(str("Eyepiece: ", EL, " long x ", ty1 - ty0, " wide x ", tz1 - tz0, " tall"));
echo(str("Lens centre thickness ", lens_tc, ", sag ", lens_sag));
echo(str("Screen glass ", focus_nominal - focus_travel / 2, "..", focus_nominal + focus_travel / 2,
         " mm behind the lens; image distance at nominal ~",
         round(1 / (1 / focus_nominal - 1 / lens_f)), " mm"));
echo(str("Eye relief ~ ", frame_clear + 12, " mm (assumes 12 mm eye-to-glasses-lens)"));

// =====================================================================
// SHARED SHAPES
// =====================================================================
module board_2d(grow = 0) {
    offset(delta = grow) union() {
        circle(r = db_r);
        translate([-db_tab_w / 2, db_bot]) square([db_tab_w, -db_bot]);
    }
}

// Place board coords (u, v) on the eyepiece's YZ plane at x.
module on_yz(x) { translate([x, 0, 0]) rotate([90, 0, 90]) children(); }

module temple_clip(len) {
    difference() {
        translate([-len, -(temple_w / 2 + wall), -clip_top])
            cube([len, temple_w + 2 * wall, 2 * clip_top]);
        translate([-len - 1, -(temple_w + fit) / 2, -(temple_h + fit) / 2])
            cube([len + 2, temple_w + fit, temple_h + fit]);
        translate([-len - 1, -(temple_w - snap) / 2, -clip_top - 1])
            cube([len + 2, temple_w - snap, wall + 2]);
        for (x = [-len + 7, -7])                                   // zip-tie grooves
            translate([x - 1.75, -temple_w, clip_top - 0.8]) cube([3.5, 2 * temple_w, 1]);
    }
}

// =====================================================================
// PRINTED PARTS
// =====================================================================

// ---------- main clip: snaps on the temple, carries the arm pivot ----------
module clip() {
    y_out = -(temple_w / 2 + wall);
    temple_clip(clip_len);
    difference() {
        union() {
            translate([-8, y_out, clip_top - 0.01]) cube([8, temple_w + 2 * wall, tab_rise + 0.01]);
            translate([-8, -4, tab_top - 3]) cube([reach + 8, 8, 3]);
            translate([hinge_x, 0, tab_top - 3]) cylinder(r = 4, h = 3);
        }
        translate([hinge_x, 0, tab_top - 5]) cylinder(d = bolt_d, h = 10);
    }
}

// ---------- arm: swivel + sideways slot at the clip, tilt hinge at the eyepiece ----------
module arm() {
    z0 = min(0, pitch_dz - 5);
    z1 = max(3, pitch_dz + 5);
    difference() {
        union() {
            hull() {
                cylinder(r = 5, h = 3);
                translate([0, arm_end - 5, 0]) cylinder(r = 5, h = 3);
            }
            translate([-5, arm_end - 5, 0]) cube([10, 5, 3]);
            translate([-5, arm_end - 3, z0]) cube([10, 3, z1 - z0]);
        }
        hull() {
            translate([0, 0, -1]) cylinder(d = bolt_d, h = 5);
            translate([0, slot_len, -1]) cylinder(d = bolt_d, h = 5);
        }
        translate([0, arm_end - 4, pitch_dz]) rotate([-90, 0, 0]) cylinder(d = bolt_d, h = 5);
    }
}

// ---------- eyepiece tube (local coords: x from the eye face, axis at y = z = 0) ----------
module eyepiece() {
    usb_x0 = xiao_back - 4;
    difference() {
        union() {
            translate([0, ty0, tz0]) cube([tube_len, ty1 - ty0, tz1 - tz0]);
            // hinge tab on top, temple side
            translate([0, ty0, tz1 - 0.01]) cube([10, 3, ep_tab_h + 5]);
            // boss under the focus screw slot
            translate([sled_x_min + focus_screw_dx - 3, focus_screw_y - 4, tz0 - 1.5])
                cube([focus_travel + 6, 8, 1.51]);
        }
        // bay, open at the front; the end wall is thin except for a ring around the lens
        difference() {
            translate([tube_wall, -bay_w / 2, bay_z0]) cube([EL, bay_w, bay_z1 - bay_z0]);
            translate([-1, 0, 0]) rotate([0, 90, 0]) cylinder(d = lens_d + 4, h = eye_wall + lens_pocket + 1);
        }
        // lens pocket (lens drops in from inside) and 45-degree aperture
        translate([eye_wall, 0, 0]) rotate([0, 90, 0]) cylinder(d = lens_d + 0.4, h = lens_pocket + 0.01);
        translate([-0.01, 0, 0]) rotate([0, 90, 0])
            cylinder(r1 = lens_d / 2 - 0.6 - eye_wall, r2 = lens_d / 2 - 0.6, h = eye_wall + 0.02);
        // hinge hole
        translate([5, ty0 - 1, tz1 + ep_tab_h]) rotate([-90, 0, 0]) cylinder(d = bolt_d, h = 5);
        // focus slot: an M2 screw through here threads into the sled
        hull() for (x = [sled_x_min, sled_x_max])
            translate([x + focus_screw_dx, focus_screw_y, tz0 - 2]) cylinder(d = bolt_d, h = tube_wall + 3);
        // USB-C access from the top, over the XIAO's connector
        translate([usb_x0, -6.5, bay_z1 - 1]) cube([12, 13, tube_wall + 2]);
        // battery wire entry, temple side
        translate([elec_x0 + 3, ty0 - 1, -6]) rotate([-90, 0, 0]) cylinder(d = 4, h = tube_wall + 2);
        // front-cap screws
        for (s = [-1, 1])
            translate([EL - cap_wall - cap_skirt / 2, s * (bay_w / 2 - 1), 0])
                rotate([-90 * s, 0, 0]) cylinder(d = bolt_d, h = tube_wall + 3);
    }
}

// ---------- screen sled: holds the round TFT, slides for focus (local x = 0 at its eye face) ----------
module sled() {
    difference() {
        translate([0, -sled_y / 2, sled_z0]) cube([sled_len, sled_y, sled_z1 - sled_z0]);
        // glass window
        translate([-0.01, 0, 0]) rotate([0, 90, 0]) cylinder(d = glass_d + 2 * fit, h = sled_plate + 0.02);
        // board pocket, open at the front
        on_yz(sled_plate) linear_extrude(sled_len) board_2d(fit);
        // screen screws (M2 self-tapping through the board's 2.5 mm holes)
        for (h = db_holes) translate([-0.01, h[0], h[1]]) rotate([0, 90, 0]) cylinder(d = tap_d, h = sled_plate + 0.02);
        // focus screw, from below
        translate([focus_screw_dx, focus_screw_y, sled_z0 - 0.01]) cylinder(d = tap_d, h = sled_floor - 0.8);
    }
}

// ---------- front cap: closes the tube, carries the XIAO stack with the camera looking out ----------
module front_cap() {
    sk_t = 1.6;
    ox = -bay_w / 2 + fit;  oz = bay_z0 + fit;
    ow = bay_w - 2 * fit;   oh = bay_z1 - bay_z0 - 2 * fit;
    hy = xiao_w / 2 + fit;  hz = xiao_l / 2 + fit;
    difference() {
        union() {
            // front plate
            translate([EL - cap_wall, ty0, tz0]) cube([cap_wall, ty1 - ty0, tz1 - tz0]);
            // locating skirt
            translate([EL - cap_wall - cap_skirt, ox, oz]) difference() {
                cube([cap_skirt + 0.01, ow, oh]);
                translate([-1, sk_t, sk_t]) cube([cap_skirt + 2, ow - 2 * sk_t, oh - 2 * sk_t]);
            }
            // screw bosses in the skirt
            for (s = [-1, 1])
                translate([EL - cap_wall - cap_skirt / 2, s * (bay_w / 2 - fit - sk_t), 0])
                    rotate([-90 * s, 0, 0]) translate([0, 0, -4]) cylinder(d = 5, h = 4.01);
            // XIAO holder: floor full length, roof over the front part only (keeps the USB-C clear)
            translate([xiao_back, -hy, -hz - 1.2]) cube([EL - cap_wall - xiao_back + 0.01, 2 * hy, 1.2]);
            translate([sense_front - 1, -hy, hz]) cube([EL - cap_wall - sense_front + 1.01, 2 * hy, 1.2]);
            // corner pads the camera board rests on
            for (sy = [-1, 1], sz = [-1, 1])
                translate([sense_front, sy * (xiao_w / 2 - 1.5) - 1.25, sz * (xiao_l / 2 - 1.5) - 1.25])
                    cube([cam_h + 0.01, 2.5, 2.5]);
        }
        // camera window
        translate([EL - cap_wall - 1, cam_dy - 7, cam_dz - 7]) cube([cap_wall + 2, 14, 14]);
        // screw pilots
        for (s = [-1, 1])
            translate([EL - cap_wall - cap_skirt / 2, s * (bay_w / 2 + 1), 0])
                rotate([90 * s, 0, 0]) cylinder(d = tap_d, h = 6);
    }
}

// ---------- battery clip: second clip behind the main one ----------
module battery_clip() {
    len = bat_l + 2 * wall + 2;
    y_out = -(temple_w / 2 + wall);
    bw = bat_t + 1;  bh = bat_w + 1;
    temple_clip(len);
    difference() {
        translate([-len, y_out - bw - wall, -bh / 2 - wall]) cube([len, bw + wall + 0.01, bh + wall]);
        translate([-len + wall, y_out - bw, -bh / 2]) cube([bat_l + 2, bw, bh + 1]);     // open on top
        translate([-wall - 1, y_out - bw + 1, bh / 2 - 6]) cube([wall + 2, bw - 2, 7]);  // wire exit
    }
}

// =====================================================================
// BOUGHT PARTS (stand-ins, not printed)
// =====================================================================
module screen_board() {            // PCB + parts, local to the sled's x
    on_yz(sled_plate) linear_extrude(db_pcb) difference() {
        board_2d();
        for (h = db_holes) translate(h) circle(d = 2.5);
    }
    // microSD socket and parts on the back
    translate([sled_plate + db_pcb, -6, -10]) cube([db_comp, 14, 15]);
    // pads
    for (i = [0 : db_pads_n - 1])
        translate([sled_plate + db_pcb - 0.01, (i - (db_pads_n - 1) / 2) * 2.54, db_pads_v])
            rotate([0, 90, 0]) cylinder(d = 1.6, h = 0.3, $fn = 12);
}
module screen_glass() {
    translate([glass_in_sled, 0, 0]) rotate([0, 90, 0]) cylinder(d = glass_d, h = glass_t);
}
module screen_active() {
    translate([glass_in_sled - 0.05, 0, 0]) rotate([0, 90, 0]) cylinder(d = active_d, h = 0.1);
}

module xiao_board() {              // local to the eyepiece
    hy = xiao_w / 2;  hz = xiao_l / 2;
    // metal can (faces the screen) and USB-C at the top end
    translate([xiao_back, -6.5, -hz + 1]) cube([xiao_can, 13, 11]);
    translate([xiao_back + xiao_can - 3.2, -4.5, hz - 7.5]) cube([3.2, 9, 7.8]);
    // XIAO PCB
    translate([xiao_back + xiao_can, -hy, -hz]) cube([xiao_pcb, xiao_w, xiao_l]);
    // board-to-board connector, camera board PCB
    translate([xiao_back + xiao_can + xiao_pcb, -4, -3]) cube([sense_gap, 8, 6]);
    translate([xiao_back + xiao_can + xiao_pcb + sense_gap, -hy, -hz]) cube([sense_pcb, xiao_w, xiao_l]);
}
module xiao_camera() {
    translate([sense_front, cam_dy - cam_mod / 2, cam_dz - cam_mod / 2]) cube([cam_h - 3, cam_mod, cam_mod]);
    translate([sense_front + cam_h - 3, cam_dy, cam_dz]) rotate([0, 90, 0]) cylinder(d = cam_lens_d, h = 3);
}

module lens() {
    rc = lens_c + lens_tc / 2 - lens_R;      // sphere for the +X surface
    lc = lens_c - lens_tc / 2 + lens_R;      // sphere for the -X surface
    intersection() {
        translate([rc, 0, 0]) sphere(r = lens_R, $fn = 160);
        translate([lc, 0, 0]) sphere(r = lens_R, $fn = 160);
        translate([lens_c - lens_tc, 0, 0]) rotate([0, 90, 0]) cylinder(d = lens_d, h = 2 * lens_tc);
    }
}

module battery() {
    y_out = -(temple_w / 2 + wall);
    translate([-bat_l - wall - 1.5, y_out - bat_t - 0.5, -bat_w / 2]) cube([bat_l, bat_t, bat_w]);
}

module m2(len) {                    // screw along +Z, head at z < 0
    translate([0, 0, -1.3]) cylinder(d = 3.8, h = 1.3, $fn = 24);
    cylinder(d = 2, h = len, $fn = 16);
}

// =====================================================================
// PLACEMENT (world coords)
// =====================================================================
module at_eyepiece()  { translate([x0, eye_to_temple, -eye_drop]) children(); }
module at_sled(f = focus) { at_eyepiece() translate([sled_x_at(f), 0, 0]) children(); }
module at_arm()       { translate([hinge_x, -slot_len / 2, tab_top]) children(); }
module at_battery()   { translate([-clip_len - 6, 0, 0]) children(); }

module screws() {
    // arm swivel (vertical) and eyepiece tilt (sideways)
    translate([hinge_x, 0, tab_top + 3]) mirror([0, 0, 1]) m2(10);
    translate([hinge_x, ep_y_min - 3, ep_hole_z]) rotate([-90, 0, 0]) m2(8);
    at_eyepiece() {
        // focus lock, from below
        translate([sled_x + focus_screw_dx, focus_screw_y, tz0 - 1.5]) m2(5);
        // front cap
        for (s = [-1, 1])
            translate([EL - cap_wall - cap_skirt / 2, s * (ty1 - 0.01), 0]) rotate([90 * s, 0, 0]) m2(6);
        // screen
        for (h = db_holes)
            translate([sled_x + sled_plate + db_pcb, h[0], h[1]]) rotate([0, -90, 0]) m2(4);
    }
}

module glasses_ref() {
    // temple arm, hinge end piece, lens rim, glasses lens
    translate([-150, -temple_w / 2, -temple_h / 2]) cube([150 + clip_to_front, temple_w, temple_h]);
    translate([clip_to_front, -temple_w / 2, -temple_h / 2]) cube([3, eye_to_temple - 22, temple_h]);
    translate([clip_to_front, eye_to_temple, -eye_drop]) rotate([0, 90, 0])
        linear_extrude(3) difference() {
            offset(r = 6) square([28, 40], center = true);
            offset(r = 4) square([28, 40], center = true);
        }
}
module glasses_lens() {
    translate([clip_to_front + 1, eye_to_temple, -eye_drop]) rotate([0, 90, 0])
        linear_extrude(1) offset(r = 4.5) square([28, 40], center = true);
}
module eye() { translate([clip_to_front - 24, eye_to_temple, -eye_drop]) sphere(d = 24); }

module assembly() {
    color("SteelBlue") clip();
    color("Orange") at_arm() arm();
    color("DimGray") at_eyepiece() eyepiece();
    color("Gold") at_sled() sled();
    color("SlateGray") at_eyepiece() front_cap();
    color("SeaGreen") at_battery() battery_clip();

    color("DarkGreen") at_sled() screen_board();
    color("Black") at_sled() screen_glass();
    color("RoyalBlue") at_sled() screen_active();
    color("MidnightBlue") at_eyepiece() xiao_board();
    color("Black") at_eyepiece() xiao_camera();
    color("LightCyan", 0.6) at_eyepiece() lens();
    color("Silver") at_battery() battery();
    color("Silver") screws();

    color("Tan", 0.35) glasses_ref();
    color("LightBlue", 0.25) glasses_lens();
    color("White", 0.5) eye();
}

// Cut-away: everything at or inward of the eyepiece axis removed, to see inside.
module section() {
    difference() {
        assembly();
        translate([-200, eye_to_temple, -100]) cube([400, 200, 200]);
    }
}

// =====================================================================
// PRINT ORIENTATION
// =====================================================================
module printable(name) {
    if (name == "clip")              rotate([90, 0, 0]) clip();
    else if (name == "arm")          arm();
    else if (name == "eyepiece")     rotate([0, -90, 0]) eyepiece();        // eye face down
    else if (name == "sled")         rotate([0, -90, 0]) sled();            // glass side down
    else if (name == "front_cap")    rotate([0, 90, 0]) front_cap();        // front face down
    else if (name == "battery_clip") rotate([90, 0, 0]) battery_clip();
}
module part_raw(name) {
    if (name == "clip") clip();
    else if (name == "arm") arm();
    else if (name == "eyepiece") eyepiece();
    else if (name == "sled") sled();
    else if (name == "front_cap") front_cap();
    else if (name == "battery_clip") battery_clip();
}

// ---------- in-place pieces for the 3D viewer ----------
module placed(name) {
    if (name == "clip") clip();
    else if (name == "arm") at_arm() arm();
    else if (name == "eyepiece") at_eyepiece() eyepiece();
    else if (name == "sled") at_sled() sled();
    else if (name == "front_cap") at_eyepiece() front_cap();
    else if (name == "battery_clip") at_battery() battery_clip();
    else if (name == "screen_board") at_sled() screen_board();
    else if (name == "screen_glass") at_sled() screen_glass();
    else if (name == "xiao") at_eyepiece() xiao_board();
    else if (name == "camera") at_eyepiece() xiao_camera();
    else if (name == "lens") at_eyepiece() lens();
    else if (name == "battery") at_battery() battery();
    else if (name == "screws") screws();
    else if (name == "glasses") glasses_ref();
    else if (name == "eye") eye();
}

// ---------- interference checks: each result should be EMPTY ----------
module check(name) {
    if (name == "eyepiece-sled-near") intersection() { at_eyepiece() eyepiece(); at_sled(-1) sled(); }
    else if (name == "eyepiece-sled-far") intersection() { at_eyepiece() eyepiece(); at_sled(1) sled(); }
    else if (name == "cap-sled-far")  intersection() { at_eyepiece() front_cap(); at_sled(1) sled(); }
    else if (name == "cap-board-far") intersection() { at_eyepiece() front_cap(); at_sled(1) screen_board(); }
    else if (name == "eyepiece-cap")  intersection() { at_eyepiece() eyepiece(); at_eyepiece() front_cap(); }
    else if (name == "eyepiece-lens") intersection() { at_eyepiece() eyepiece(); at_eyepiece() lens(); }
    else if (name == "eyepiece-board") intersection() { at_eyepiece() eyepiece(); at_sled(1) screen_board(); }
    else if (name == "eyepiece-glass") intersection() { at_eyepiece() eyepiece(); at_sled(-1) screen_glass(); }
    else if (name == "sled-board")    intersection() { at_sled() sled(); at_sled() screen_board(); }
    else if (name == "sled-glass")    intersection() { at_sled() sled(); at_sled() screen_glass(); }
    else if (name == "lens-glass-near") intersection() { at_eyepiece() lens(); at_sled(-1) screen_glass(); }
    else if (name == "cap-xiao")      intersection() { at_eyepiece() front_cap(); at_eyepiece() xiao_board(); }
    else if (name == "cap-camera")    intersection() { at_eyepiece() front_cap(); at_eyepiece() xiao_camera(); }
    else if (name == "eyepiece-xiao") intersection() { at_eyepiece() eyepiece(); at_eyepiece() xiao_board(); }
    else if (name == "board-xiao-far") intersection() { at_sled(1) screen_board(); at_eyepiece() xiao_board(); }
    else if (name == "arm-eyepiece")  intersection() { at_arm() arm(); at_eyepiece() eyepiece(); }
    else if (name == "arm-clip")      intersection() { at_arm() arm(); clip(); }
    else if (name == "arm-glasses")   intersection() { at_arm() arm(); glasses_ref(); }
    else if (name == "clip-glasses")  intersection() { clip(); glasses_ref(); }
    else if (name == "eyepiece-glasses") intersection() { at_eyepiece() eyepiece(); glasses_ref(); }
    else if (name == "eyepiece-eye")  intersection() { at_eyepiece() eyepiece(); eye(); }
    else if (name == "batclip-glasses") intersection() { at_battery() battery_clip(); glasses_ref(); }
    else if (name == "batclip-battery") intersection() { at_battery() battery_clip(); at_battery() battery(); }
    else if (name == "batclip-clip")  intersection() { at_battery() battery_clip(); clip(); }
}

// =====================================================================
if (part == "assembly") assembly();
else if (part == "section") section();
else if (part == "clip" || part == "arm" || part == "eyepiece" || part == "sled"
         || part == "front_cap" || part == "battery_clip") {
    if (print_orient) printable(part); else part_raw(part);
}
else {
    // "placed_<name>" and "check_<name>" are used by render.sh
    _p = "placed_"; _c = "check_";
    if (len(part) > len(_p) && substr(part, 0, len(_p)) == _p) placed(substr(part, len(_p)));
    else if (len(part) > len(_c) && substr(part, 0, len(_c)) == _c) check(substr(part, len(_c)));
    else echo(str("Unknown part: ", part));
}

function substr(s, start, n = -1) =
    let(end = n < 0 ? len(s) : min(len(s), start + n))
    start >= end ? "" : chr([for (i = [start : end - 1]) ord(s[i])]);

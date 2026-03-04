use std::env;
use std::fs;
use std::path::Path;

fn main() {
    let spec_path = "../../api-contracts/openapi.yaml";
    
    // Пересобираем если файл OpenAPI изменился
    println!("cargo:rerun-if-changed={}", spec_path);

    // Читаем спецификацию
    let file = fs::File::open(spec_path).expect("Failed to open openapi.yaml");
    let spec: openapiv3::OpenAPI = serde_yaml::from_reader(file).expect("Failed to parse openapi.yaml");

    // Настраиваем генератор Progenitor
    let mut generator = progenitor::Generator::default();
    
    // Генерируем Rust токены
    let tokens = generator.generate_tokens(&spec).expect("Failed to generate Rust code from OpenAPI");

    // Форматируем с помощью prettyplease или конвертируем в string
    let generated_code = tokens.to_string();

    // Записываем в OUT_DIR
    let out_dir = env::var_os("OUT_DIR").expect("OUT_DIR not set");
    let dest_path = Path::new(&out_dir).join("api_generated.rs");
    
    fs::write(&dest_path, generated_code).expect("Failed to write api_generated.rs");
}
